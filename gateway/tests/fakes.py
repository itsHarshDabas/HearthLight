"""Fake LM Studio and ComfyUI servers that mimic the real HTTP APIs closely enough
for end-to-end tests (and for trying the Android app without a GPU: see dev/run_fakes.py)."""

from __future__ import annotations

import asyncio
import base64
import json
import socket
import threading
import time
import uuid
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

# 1x1 PNG
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def fake_lmstudio(token_delay: float = 0.02) -> FastAPI:
    app = FastAPI()
    app.state.requests = []
    app.state.disconnected = 0

    @app.get("/v1/models")
    async def models():
        return {"object": "list", "data": [
            {"id": "qwen3-8b", "object": "model", "owned_by": "organization_owner"},
            {"id": "deepthink-r1", "object": "model", "owned_by": "organization_owner"},
            {"id": "text-embedding-nomic-embed-text-v1.5", "object": "model", "owned_by": "organization_owner"},
        ]}

    @app.post("/v1/chat/completions")
    async def chat(request: Request):
        body = await request.json()
        app.state.requests.append(body)
        model = body.get("model")
        if model not in ("qwen3-8b", "deepthink-r1"):
            return JSONResponse({"error": f"model '{model}' not found"}, status_code=404)
        last = body["messages"][-1]["content"]
        if isinstance(last, list):  # multimodal content parts
            last = " ".join(p.get("text", "") for p in last if isinstance(p, dict))
        words = ["Echo:"] + str(last).split()
        if "LONG" in str(last):
            words = words + ["word"] * 400
        cid = "chatcmpl-" + uuid.uuid4().hex[:8]
        if not body.get("stream"):
            return {"id": cid, "object": "chat.completion", "created": int(time.time()), "model": model,
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": " ".join(words)},
                                 "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 5, "completion_tokens": len(words), "total_tokens": 5 + len(words)}}

        async def gen():
            def chunk(delta, finish=None):
                return "data: " + json.dumps({"id": cid, "object": "chat.completion.chunk", "created": int(time.time()),
                                              "model": model, "choices": [{"index": 0, "delta": delta,
                                                                           "finish_reason": finish}]}) + "\n\n"
            try:
                yield chunk({"role": "assistant", "content": ""})
                if model == "deepthink-r1":
                    for w in ["Let", " me", " think."]:
                        yield chunk({"reasoning_content": w})
                        await asyncio.sleep(token_delay)
                for i, w in enumerate(words):
                    yield chunk({"content": (" " if i else "") + w})
                    await asyncio.sleep(token_delay)
                yield chunk({}, "stop")
                yield "data: [DONE]\n\n"
            except asyncio.CancelledError:
                app.state.disconnected += 1
                raise

        return StreamingResponse(gen(), media_type="text/event-stream")

    @app.post("/v1/embeddings")
    async def embeddings(request: Request):
        body = await request.json()
        return {"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
                "model": body.get("model")}

    return app


def fake_comfyui(render_seconds: float = 0.3) -> FastAPI:
    """Single-GPU style queue: one prompt renders at a time."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = asyncio.create_task(runner())
        yield
        task.cancel()

    app = FastAPI(lifespan=lifespan)
    app.state.prompts = {}   # id -> graph
    app.state.pending = []   # ids
    app.state.running = None
    app.state.history = {}
    app.state.interrupted = set()
    lock = asyncio.Lock()

    async def runner():
        while True:
            await asyncio.sleep(0.02)
            async with lock:
                if app.state.running or not app.state.pending:
                    continue
                pid = app.state.pending.pop(0)
                app.state.running = pid
            started = time.monotonic()
            while time.monotonic() - started < render_seconds and pid not in app.state.interrupted:
                await asyncio.sleep(0.02)
            graph = app.state.prompts[pid]
            texts = " ".join(str(n["inputs"].get("text", "")) for n in graph.values())
            async with lock:
                if pid in app.state.interrupted:
                    app.state.history[pid] = {"outputs": {}, "status": {
                        "status_str": "error", "completed": False,
                        "messages": [["execution_start", {}], ["execution_interrupted", {"prompt_id": pid}]]}}
                elif "FAIL" in texts:
                    app.state.history[pid] = {"outputs": {}, "status": {
                        "status_str": "error", "completed": False,
                        "messages": [["execution_error", {"node_type": "KSampler",
                                                          "exception_message": "CUDA out of memory"}]]}}
                else:
                    batch = 1
                    for n in graph.values():
                        if "Latent" in n["class_type"]:
                            batch = int(n["inputs"].get("batch_size", 1))
                    imgs = [{"filename": f"gw_{pid[:6]}_{i}_.png", "subfolder": "", "type": "output"} for i in range(batch)]
                    app.state.history[pid] = {"prompt": [0, pid, graph, {}, ["9"]], "outputs": {"9": {"images": imgs}},
                                              "status": {"status_str": "success", "completed": True, "messages": []}}
                app.state.running = None

    @app.post("/prompt")
    async def prompt(request: Request):
        body = await request.json()
        graph = body.get("prompt") or {}
        errors = {}
        for nid, node in graph.items():
            for k, v in node.get("inputs", {}).items():
                if isinstance(v, str) and "{{" in v:
                    errors[nid] = {"class_type": node["class_type"], "errors": [{"message": f"unreplaced placeholder in {k}"}]}
            if node.get("class_type") == "CheckpointLoaderSimple" and node["inputs"].get("ckpt_name") == "missing.safetensors":
                return JSONResponse({"error": {"type": "prompt_outputs_failed_validation",
                                               "message": "Prompt outputs failed validation", "details": ""},
                                     "node_errors": {nid: {"class_type": "CheckpointLoaderSimple", "errors": [
                                         {"message": "Value not in list", "details": "ckpt_name: 'missing.safetensors' not in []"}]}}},
                                    status_code=400)
        if errors:
            return JSONResponse({"error": {"message": "invalid prompt"}, "node_errors": errors}, status_code=400)
        pid = str(uuid.uuid4())
        async with lock:
            app.state.prompts[pid] = graph
            app.state.pending.append(pid)
        return {"prompt_id": pid, "number": len(app.state.prompts), "node_errors": {}}

    @app.get("/queue")
    async def queue():
        running = [[0, app.state.running, {}, {}, []]] if app.state.running else []
        pending = [[i + 1, pid, {}, {}, []] for i, pid in enumerate(app.state.pending)]
        return {"queue_running": running, "queue_pending": pending}

    @app.post("/queue")
    async def queue_edit(request: Request):
        body = await request.json()
        async with lock:
            for pid in body.get("delete", []):
                if pid in app.state.pending:
                    app.state.pending.remove(pid)
        return {}

    @app.post("/interrupt")
    async def interrupt(request: Request):
        try:
            body = await request.json()
        except Exception:
            body = {}
        pid = body.get("prompt_id") or app.state.running
        if pid:
            app.state.interrupted.add(pid)
        return {}

    @app.get("/history/{pid}")
    async def history(pid: str):
        return {pid: app.state.history[pid]} if pid in app.state.history else {}

    @app.get("/view")
    async def view(filename: str, subfolder: str = "", type: str = "output"):
        return Response(PNG, media_type="image/png")

    @app.get("/system_stats")
    async def system_stats():
        return {"system": {"os": "nt"}, "devices": [{"name": "cuda:0 NVIDIA GeForce RTX 4090", "type": "cuda",
                                                     "vram_total": 25757220864, "vram_free": 20000000000}]}

    @app.get("/object_info/CheckpointLoaderSimple")
    async def obj():
        return {"CheckpointLoaderSimple": {"input": {"required": {"ckpt_name": [["sd_xl_base_1.0.safetensors",
                                                                                   "flux1-dev-fp8.safetensors"]]}}}}

    return app


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ServerThread:
    def __init__(self, app, port: int | None = None):
        self.port = port or free_port()
        self.server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=self.port, log_level="warning",
                                                    lifespan="on"))
        self.thread = threading.Thread(target=self.server.run, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def __enter__(self):
        self.thread.start()
        deadline = time.time() + 10
        while not self.server.started:
            if time.time() > deadline:
                raise RuntimeError("server did not start")
            time.sleep(0.02)
        return self

    def __exit__(self, *exc):
        self.server.should_exit = True
        self.thread.join(timeout=10)
