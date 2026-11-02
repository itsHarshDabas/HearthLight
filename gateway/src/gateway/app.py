"""FastAPI application: routes and wiring."""

from __future__ import annotations

import base64
import json
import logging
import time
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from . import __version__
from .comfy import ComfyClient
from .config import Settings, prepare_runtime
from .jobs import FINAL, SUCCEEDED, Job, JobDB, JobManager
from .security import openai_error, require_key, sign_path, verify_signed
from .upstreams import ChatRouter
from .workflows import WorkflowError, WorkflowStore

log = logging.getLogger("gateway")

class ImageParams(BaseModel):
    model_config = ConfigDict(extra="ignore")

    prompt: str = Field(min_length=1, max_length=20000)
    negative_prompt: str | None = Field(default=None, max_length=20000)
    workflow: str | None = None
    model: str | None = None  # OpenAI-style alias for `workflow`
    n: int = Field(default=1, ge=1, le=8)
    size: str | None = None  # "1024x1024"
    width: int | None = Field(default=None, ge=64, le=4096)
    height: int | None = Field(default=None, ge=64, le=4096)
    steps: int | None = Field(default=None, ge=1, le=200)
    cfg: float | None = Field(default=None, ge=0, le=30)
    seed: int | None = None
    checkpoint: str | None = None
    response_format: str | None = None  # "url" | "b64_json" (sync endpoint only)

    def to_job(self) -> tuple[str | None, dict[str, Any]]:
        width, height = self.width, self.height
        if self.size and not (width or height):
            try:
                w, h = self.size.lower().split("x")
                width, height = int(w), int(h)
            except ValueError as e:
                raise openai_error(400, "size must look like '1024x1024'") from e
        params = {
            "prompt": self.prompt, "negative_prompt": self.negative_prompt, "n": self.n,
            "width": width, "height": height, "steps": self.steps, "cfg": self.cfg,
            "seed": self.seed, "checkpoint": self.checkpoint,
        }
        return (self.workflow or self.model), params


def create_app(settings: Settings) -> FastAPI:
    prepare_runtime(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        limits = httpx.Limits(max_connections=50, max_keepalive_connections=10)
        app.state.http = httpx.AsyncClient(timeout=httpx.Timeout(30.0, read=None), limits=limits)
        app.state.router = ChatRouter(settings.chat_upstreams, app.state.http)
        app.state.workflows = WorkflowStore(settings.comfyui.workflows_dir)
        app.state.comfy = ComfyClient(settings.comfyui.base_url, app.state.http)
        app.state.db = JobDB(settings.db_path)
        app.state.jobs = JobManager(
            app.state.db, app.state.comfy, app.state.workflows, settings.images_dir,
            workers=settings.comfyui.max_concurrent_jobs, poll_interval=settings.comfyui.poll_interval,
            job_timeout=settings.comfyui.job_timeout,
        )
        if settings.comfyui.enabled:
            await app.state.jobs.start()
        try:
            yield
        finally:
            await app.state.jobs.stop()
            await app.state.http.aclose()
            app.state.db.close()

    app = FastAPI(
        title="Local AI Gateway", version=__version__, lifespan=lifespan,
        docs_url="/docs" if settings.enable_docs else None,
        redoc_url=None, openapi_url="/openapi.json" if settings.enable_docs else None,
    )
    app.state.settings = settings

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware, allow_origins=settings.cors_origins, allow_credentials=False,
            allow_methods=["GET", "POST", "DELETE", "OPTIONS"], allow_headers=["Authorization", "Content-Type", "X-API-Key"],
        )

    @app.exception_handler(HTTPException)
    async def _http_exc(request: Request, exc: HTTPException):
        detail = exc.detail if isinstance(exc.detail, dict) and "error" in exc.detail else {
            "error": {"message": str(exc.detail), "type": "invalid_request_error", "code": None}
        }
        return JSONResponse(detail, status_code=exc.status_code, headers=getattr(exc, "headers", None))

    auth = [Depends(require_key)]

    # ------------------------------------------------------------------ health
    @app.get("/health")
    async def health():
        return {"ok": True, "version": __version__}

    @app.get("/v1/status", dependencies=auth)
    async def status(request: Request):
        router: ChatRouter = request.app.state.router
        await router.models(refresh=True)
        comfy_info: dict[str, Any] = {"enabled": settings.comfyui.enabled}
        if settings.comfyui.enabled:
            try:
                stats = await request.app.state.comfy.system_stats()
                comfy_info.update(status="ok", devices=[
                    {k: d.get(k) for k in ("name", "type", "vram_total", "vram_free")} for d in stats.get("devices", [])
                ])
            except httpx.HTTPError as e:
                comfy_info.update(status=f"unreachable: {e}")
        db: JobDB = request.app.state.db
        return {
            "version": __version__,
            "chat_upstreams": [{"name": u.name, "status": router.last_status.get(u.name, "unknown")} for u in router.upstreams],
            "comfyui": comfy_info,
            "jobs": {"queued": db.count("queued"), "running": db.count("running")},
            "workflow_errors": request.app.state.workflows.errors,
        }

    # ------------------------------------------------------------------ chat
    @app.get("/v1/models", dependencies=auth)
    async def list_models(request: Request):
        entries = await request.app.state.router.models()
        data = []
        for e in entries:
            m = dict(e.raw)
            m.setdefault("object", "model")
            m["owned_by"] = m.get("owned_by") or e.upstream.name
            m["upstream"] = e.upstream.name
            data.append(m)
        return {"object": "list", "data": data}

    async def _proxy_json(request: Request, path: str, body: dict) -> Response:
        router: ChatRouter = request.app.state.router
        if not body.get("model"):
            dm = await router.default_model(settings.default_model)
            if dm:
                body["model"] = dm
        stream = bool(body.get("stream"))
        last_err: Exception | None = None
        for up in await router.candidates(body.get("model")):
            url = f"{up.base_url}{path}"
            headers = {**router.headers(up), "Content-Type": "application/json"}
            timeout = httpx.Timeout(30.0, read=up.timeout)
            try:
                if not stream:
                    r = await request.app.state.http.post(url, json=body, headers=headers, timeout=timeout)
                    return Response(r.content, status_code=r.status_code,
                                    media_type=r.headers.get("content-type", "application/json"))
                req = request.app.state.http.build_request("POST", url, json=body, headers=headers, timeout=timeout)
                r = await request.app.state.http.send(req, stream=True)
            except (httpx.ConnectError, httpx.ConnectTimeout) as e:
                router.last_status[up.name] = f"unreachable: {e}"
                last_err = e
                continue  # try the next upstream serving this model
            except httpx.HTTPError as e:
                raise openai_error(502, f"upstream '{up.name}' error: {e}", "upstream_error") from e

            if r.status_code != 200:
                content = await r.aread()
                await r.aclose()
                return Response(content, status_code=r.status_code,
                                media_type=r.headers.get("content-type", "application/json"))

            async def relay(resp: httpx.Response = r, name: str = up.name):
                try:
                    async for chunk in resp.aiter_bytes():
                        yield chunk
                except httpx.HTTPError as e:
                    # Headers are already sent; report the failure in-band in SSE form.
                    msg = json.dumps({"error": {"message": f"upstream '{name}' stream broke: {e}", "type": "upstream_error"}})
                    yield f"data: {msg}\n\n".encode()
                finally:
                    await resp.aclose()  # also stops generation upstream when the client goes away

            return StreamingResponse(relay(), status_code=200, media_type="text/event-stream",
                                     headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
        raise openai_error(503, f"No chat upstream reachable ({last_err}). Is LM Studio's server running?", "upstream_unavailable")

    async def _json_body(request: Request) -> dict:
        try:
            body = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            raise openai_error(400, "request body must be JSON") from e
        if not isinstance(body, dict):
            raise openai_error(400, "request body must be a JSON object")
        return body

    @app.post("/v1/chat/completions", dependencies=auth)
    async def chat_completions(request: Request):
        body = await _json_body(request)
        if not isinstance(body.get("messages"), list) or not body["messages"]:
            raise openai_error(400, "'messages' must be a non-empty array")
        return await _proxy_json(request, "/chat/completions", body)

    @app.post("/v1/completions", dependencies=auth)
    async def completions(request: Request):
        return await _proxy_json(request, "/completions", await _json_body(request))

    @app.post("/v1/embeddings", dependencies=auth)
    async def embeddings(request: Request):
        body = await _json_body(request)
        body.pop("stream", None)
        return await _proxy_json(request, "/embeddings", body)

    # ------------------------------------------------------------------ images
    def _require_comfy() -> None:
        if not settings.comfyui.enabled:
            raise openai_error(404, "image generation is disabled in config ([comfyui] enabled = false)")

    def _base_url(request: Request) -> str:
        if settings.public_base_url:
            return settings.public_base_url
        proto = request.headers.get("x-forwarded-proto") or request.url.scheme
        host = request.headers.get("x-forwarded-host") or request.headers.get("host") or request.url.netloc
        return f"{proto}://{host}"

    def _job_out(request: Request, job: Job) -> dict:
        jm: JobManager = request.app.state.jobs
        base = _base_url(request)
        images = []
        for img in job.images:
            path = sign_path(settings, f"/v1/files/{job.id}/{img['file']}")
            images.append({"url": base + path, "path": path, "seed": img.get("seed")})
        out = {
            "id": job.id, "object": "image.job", "status": job.status, "workflow": job.workflow,
            "params": job.params, "created_at": int(job.created_at),
            "started_at": int(job.started_at) if job.started_at else None,
            "finished_at": int(job.finished_at) if job.finished_at else None,
            "progress": job.progress, "error": job.error, "images": images,
        }
        if job.status == "queued":
            out["queue_position"] = jm.queue_position(job)
        return out

    def _create_job(request: Request, p: ImageParams) -> Job:
        _require_comfy()
        workflow, params = p.to_job()
        try:
            return request.app.state.jobs.create(workflow, params)
        except WorkflowError as e:
            raise openai_error(400, str(e)) from e

    @app.get("/v1/workflows", dependencies=auth)
    async def list_workflows(request: Request):
        _require_comfy()
        store: WorkflowStore = request.app.state.workflows
        return {"object": "list", "data": [w.public() for w in store.list()], "errors": store.errors}

    @app.get("/v1/comfy/checkpoints", dependencies=auth)
    async def list_checkpoints(request: Request):
        _require_comfy()
        try:
            return {"object": "list", "data": await request.app.state.comfy.checkpoints()}
        except httpx.HTTPError as e:
            raise openai_error(503, f"ComfyUI unreachable: {e}", "upstream_unavailable") from e

    @app.post("/v1/images/jobs", dependencies=auth, status_code=202)
    async def create_image_job(request: Request, p: ImageParams):
        return _job_out(request, _create_job(request, p))

    @app.get("/v1/images/jobs", dependencies=auth)
    async def list_image_jobs(request: Request, limit: int = 50, before: float | None = None, status: str | None = None):
        jobs = request.app.state.db.list(limit=max(1, min(limit, 200)), before=before, status=status)
        data = [_job_out(request, j) for j in jobs]
        return {"object": "list", "data": data, "has_more": len(jobs) == limit,
                "next_before": jobs[-1].created_at if jobs else None}

    @app.get("/v1/images/jobs/{job_id}", dependencies=auth)
    async def get_image_job(request: Request, job_id: str, wait: float = 0):
        jm: JobManager = request.app.state.jobs
        job = await jm.wait(job_id, min(max(wait, 0), 60)) if wait > 0 else jm.get(job_id)
        if job is None:
            raise openai_error(404, "job not found")
        return _job_out(request, job)

    @app.post("/v1/images/jobs/{job_id}/cancel", dependencies=auth)
    async def cancel_image_job(request: Request, job_id: str):
        job = await request.app.state.jobs.cancel(job_id)
        if job is None:
            raise openai_error(404, "job not found")
        return _job_out(request, request.app.state.jobs.get(job_id) or job)

    @app.delete("/v1/images/jobs/{job_id}", dependencies=auth)
    async def delete_image_job(request: Request, job_id: str):
        if not await request.app.state.jobs.delete(job_id):
            raise openai_error(404, "job not found")
        return {"id": job_id, "object": "image.job", "deleted": True}

    @app.post("/v1/images/generations", dependencies=auth)
    async def images_generations(request: Request, p: ImageParams):
        """OpenAI-compatible, synchronous: waits for the job (up to the job timeout)."""
        job = _create_job(request, p)
        jm: JobManager = request.app.state.jobs
        deadline = time.monotonic() + settings.comfyui.job_timeout + 30
        while job and job.status not in FINAL and time.monotonic() < deadline:
            if await request.is_disconnected():
                break  # the job keeps running; it shows up in /v1/images/jobs
            job = await jm.wait(job.id, 5)
        if job is None or job.status != SUCCEEDED:
            status = 504 if job and job.status not in FINAL else 500
            msg = (job.error if job else None) or f"image job {job.id if job else '?'} is {job.status if job else 'missing'}"
            raise openai_error(status, f"{msg} (job id: {job.id if job else '-'})", "image_generation_error")
        data = []
        for img in job.images:
            if p.response_format == "b64_json":
                f = jm.image_path(job.id, img["file"])
                data.append({"b64_json": base64.b64encode(f.read_bytes()).decode() if f else None,
                             "revised_prompt": p.prompt})
            else:
                path = sign_path(settings, f"/v1/files/{job.id}/{img['file']}")
                data.append({"url": _base_url(request) + path, "revised_prompt": p.prompt})
        return {"created": int(job.created_at), "data": data, "job_id": job.id}

    @app.get("/v1/files/{job_id}/{filename}")
    async def get_file(request: Request, job_id: str, filename: str, exp: str | None = None, sig: str | None = None):
        path = f"/v1/files/{job_id}/{filename}"
        if not verify_signed(settings, path, exp, sig):
            require_key(request)  # fall back to normal API-key auth
        f = request.app.state.jobs.image_path(job_id, filename)
        if f is None:
            raise openai_error(404, "file not found")
        return FileResponse(f, headers={"Cache-Control": "private, max-age=86400"})

    return app
