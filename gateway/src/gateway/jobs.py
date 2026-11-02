"""Image job queue backed by SQLite.

Jobs survive gateway restarts and phone disconnects: the phone only submits a job and
polls it; the gateway drives ComfyUI and stores the resulting images on disk.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import shutil
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx

from .comfy import ComfyClient, ComfyError, history_status, output_images
from .workflows import WorkflowError, WorkflowStore

log = logging.getLogger("gateway.jobs")

QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED = "queued", "running", "succeeded", "failed", "cancelled"
FINAL = {SUCCEEDED, FAILED, CANCELLED}
SAFE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
MAX_N = 8


@dataclass
class Job:
    id: str
    workflow: str
    params: dict[str, Any]
    status: str = QUEUED
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    error: str | None = None
    progress: str | None = None
    prompt_ids: list[str] = field(default_factory=list)
    seeds: list[int | None] = field(default_factory=list)
    images: list[dict] = field(default_factory=list)  # {"file": name, "seed": int|None}


class JobDB:
    def __init__(self, path: Path):
        self._lock = threading.Lock()
        self.conn = sqlite3.connect(str(path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        with self._lock:
            self.conn.execute("PRAGMA journal_mode=WAL")
            self.conn.execute(
                """CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, workflow TEXT NOT NULL, params TEXT NOT NULL,
                    status TEXT NOT NULL, created_at REAL NOT NULL, started_at REAL, finished_at REAL,
                    error TEXT, progress TEXT, prompt_ids TEXT NOT NULL DEFAULT '[]',
                    seeds TEXT NOT NULL DEFAULT '[]', images TEXT NOT NULL DEFAULT '[]')"""
            )
            self.conn.execute("CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created_at)")
            self.conn.commit()

    @staticmethod
    def _row(r: sqlite3.Row) -> Job:
        return Job(
            id=r["id"], workflow=r["workflow"], params=json.loads(r["params"]), status=r["status"],
            created_at=r["created_at"], started_at=r["started_at"], finished_at=r["finished_at"],
            error=r["error"], progress=r["progress"], prompt_ids=json.loads(r["prompt_ids"]),
            seeds=json.loads(r["seeds"]), images=json.loads(r["images"]),
        )

    def save(self, j: Job) -> None:
        with self._lock:
            self.conn.execute(
                """INSERT INTO jobs (id, workflow, params, status, created_at, started_at, finished_at, error,
                                     progress, prompt_ids, seeds, images)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(id) DO UPDATE SET status=excluded.status, started_at=excluded.started_at,
                     finished_at=excluded.finished_at, error=excluded.error, progress=excluded.progress,
                     prompt_ids=excluded.prompt_ids, seeds=excluded.seeds, images=excluded.images""",
                (j.id, j.workflow, json.dumps(j.params), j.status, j.created_at, j.started_at, j.finished_at,
                 j.error, j.progress, json.dumps(j.prompt_ids), json.dumps(j.seeds), json.dumps(j.images)),
            )
            self.conn.commit()

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            r = self.conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return self._row(r) if r else None

    def list(self, limit: int = 50, before: float | None = None, status: str | None = None) -> list[Job]:
        q, args = "SELECT * FROM jobs WHERE 1=1", []
        if before is not None:
            q += " AND created_at < ?"
            args.append(before)
        if status:
            q += " AND status = ?"
            args.append(status)
        q += " ORDER BY created_at DESC LIMIT ?"
        args.append(limit)
        with self._lock:
            return [self._row(r) for r in self.conn.execute(q, args).fetchall()]

    def unfinished(self) -> list[Job]:
        with self._lock:
            rows = self.conn.execute(
                "SELECT * FROM jobs WHERE status IN (?, ?) ORDER BY created_at ASC", (QUEUED, RUNNING)
            ).fetchall()
        return [self._row(r) for r in rows]

    def count(self, status: str) -> int:
        with self._lock:
            return self.conn.execute("SELECT COUNT(*) FROM jobs WHERE status=?", (status,)).fetchone()[0]

    def delete(self, job_id: str) -> None:
        with self._lock:
            self.conn.execute("DELETE FROM jobs WHERE id=?", (job_id,))
            self.conn.commit()

    def close(self) -> None:
        with self._lock:
            self.conn.close()


class JobManager:
    def __init__(self, db: JobDB, comfy: ComfyClient, store: WorkflowStore, images_dir: Path, *,
                 workers: int = 1, poll_interval: float = 1.0, job_timeout: float = 1800.0):
        self.db = db
        self.comfy = comfy
        self.store = store
        self.images_dir = images_dir
        self.n_workers = max(1, workers)
        self.poll_interval = poll_interval
        self.job_timeout = job_timeout
        self.client_id = "local-ai-gateway-" + uuid.uuid4().hex[:8]
        self.queue: asyncio.Queue[str] = asyncio.Queue()
        self._cancel: set[str] = set()
        self._events: dict[str, asyncio.Event] = {}
        self._tasks: list[asyncio.Task] = []

    # -- lifecycle --------------------------------------------------------
    async def start(self) -> None:
        for job in self.db.unfinished():
            log.info("resuming job %s (%s)", job.id, job.status)
            self.queue.put_nowait(job.id)
        self._tasks = [asyncio.create_task(self._worker(i)) for i in range(self.n_workers)]

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks = []

    # -- public API ---------------------------------------------------------
    def create(self, workflow: str | None, params: dict[str, Any]) -> Job:
        wf = self.store.get(workflow)  # raises WorkflowError
        n = int(params.pop("n", 1) or 1)
        if not 1 <= n <= MAX_N:
            raise WorkflowError(f"n must be between 1 and {MAX_N}")
        params = {k: v for k, v in params.items() if v is not None}
        wf.build(dict(params))  # validate early so bad requests fail with 400, not later
        params["n"] = n
        job = Job(id="img_" + uuid.uuid4().hex[:20], workflow=wf.name, params=params)
        self.db.save(job)
        self.queue.put_nowait(job.id)
        return job

    def get(self, job_id: str) -> Job | None:
        return self.db.get(job_id)

    def queue_position(self, job: Job) -> int | None:
        if job.status != QUEUED:
            return None
        ahead = [j for j in self.db.unfinished() if j.created_at < job.created_at]
        return len(ahead)

    async def cancel(self, job_id: str) -> Job | None:
        job = self.db.get(job_id)
        if job is None or job.status in FINAL:
            return job
        if job.status == QUEUED:
            job.status, job.finished_at, job.progress = CANCELLED, time.time(), None
            self.db.save(job)
            self._notify(job_id)
        else:
            self._cancel.add(job_id)
        return job

    async def delete(self, job_id: str) -> bool:
        job = self.db.get(job_id)
        if job is None:
            return False
        if job.status not in FINAL:
            await self.cancel(job_id)
        self.db.delete(job_id)
        shutil.rmtree(self.images_dir / job_id, ignore_errors=True)
        return True

    async def wait(self, job_id: str, timeout: float) -> Job | None:
        job = self.db.get(job_id)
        if job is None or job.status in FINAL:
            return job
        ev = self._events.setdefault(job_id, asyncio.Event())
        try:
            await asyncio.wait_for(ev.wait(), timeout)
        except asyncio.TimeoutError:
            pass
        return self.db.get(job_id)

    def image_path(self, job_id: str, filename: str) -> Path | None:
        if not re.fullmatch(r"img_[0-9a-f]{20}", job_id) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", filename):
            return None
        p = self.images_dir / job_id / filename
        return p if p.is_file() else None

    # -- worker -------------------------------------------------------------
    def _notify(self, job_id: str) -> None:
        ev = self._events.pop(job_id, None)
        if ev:
            ev.set()

    async def _worker(self, idx: int) -> None:
        while True:
            job_id = await self.queue.get()
            try:
                job = self.db.get(job_id)
                if job is None or job.status in FINAL:
                    continue
                await self._run(job)
            except asyncio.CancelledError:
                raise
            except Exception:  # never let one job kill the worker
                log.exception("job %s crashed", job_id)
                job = self.db.get(job_id)
                if job and job.status not in FINAL:
                    job.status, job.error, job.finished_at = FAILED, "internal error (see gateway log)", time.time()
                    self.db.save(job)
            finally:
                self._cancel.discard(job_id)
                self._notify(job_id)
                self.queue.task_done()

    def _plan_runs(self, job: Job, wf) -> list[dict]:
        params = {k: v for k, v in job.params.items() if k != "n"}
        n = int(job.params.get("n", 1))
        if n == 1:
            return [params]
        if "batch_size" in wf.targets and "batch_size" not in params:
            return [{**params, "batch_size": n}]
        runs = []
        base_seed = params.get("seed")
        for i in range(n):
            p = dict(params)
            if base_seed is not None and int(base_seed) >= 0:
                p["seed"] = int(base_seed) + i
            else:
                p.pop("seed", None)
            runs.append(p)
        return runs

    async def _run(self, job: Job) -> None:
        job.status = RUNNING
        job.started_at = job.started_at or time.time()
        job.progress = "starting"
        self.db.save(job)
        deadline = time.monotonic() + self.job_timeout
        try:
            wf = self.store.get(job.workflow)
            runs = self._plan_runs(job, wf)
            out_dir = self.images_dir / job.id
            out_dir.mkdir(parents=True, exist_ok=True)
            done_files = {img["file"] for img in job.images}
            for i, run_params in enumerate(runs):
                if job.id in self._cancel:
                    break
                if any(f.startswith(f"{i}_") for f in done_files):
                    continue  # finished before a restart
                pid = job.prompt_ids[i] if i < len(job.prompt_ids) else None
                if pid and not await self._comfy_knows(pid):
                    pid = None
                if pid is None:
                    if i < len(job.seeds) and job.seeds[i] is not None:
                        run_params = {**run_params, "seed": job.seeds[i]}
                    graph, seed = wf.build(run_params)
                    pid = await self.comfy.submit(graph, self.client_id)
                    _set(job.prompt_ids, i, pid)
                    _set(job.seeds, i, seed)
                    self.db.save(job)
                entry = await self._wait_prompt(job, pid, deadline, label=f"{i + 1}/{len(runs)}" if len(runs) > 1 else "")
                if entry is None:
                    break  # cancelled
                for k, img in enumerate(output_images(entry)):
                    data = await self.comfy.view(img["filename"], img.get("subfolder", ""), img.get("type", "output"))
                    ext = Path(img["filename"]).suffix.lower()
                    ext = ext if ext in SAFE_EXT else ".png"
                    name = f"{i}_{k}{ext}"
                    (out_dir / name).write_bytes(data)
                    job.images.append({"file": name, "seed": job.seeds[i] if i < len(job.seeds) else None})
                self.db.save(job)

            if job.id in self._cancel:
                job.status = CANCELLED
            elif not job.images:
                job.status, job.error = FAILED, "ComfyUI finished but produced no images (does the workflow have a SaveImage node?)"
            else:
                job.status = SUCCEEDED
        except (ComfyError, WorkflowError) as e:
            job.status, job.error = FAILED, str(e)
        except _JobTimeout:
            job.status, job.error = FAILED, f"timed out after {int(self.job_timeout)}s"
            await self._safe_cancel(job)
        job.progress = None
        job.finished_at = time.time()
        self.db.save(job)
        log.info("job %s -> %s %s", job.id, job.status, job.error or "")

    async def _comfy_knows(self, pid: str) -> bool:
        try:
            return (await self.comfy.history(pid)) is not None or (await self.comfy.queue_state(pid)) is not None
        except httpx.HTTPError:
            return True  # ComfyUI temporarily down: assume it still has it and keep waiting

    async def _safe_cancel(self, job: Job) -> None:
        for pid in job.prompt_ids:
            try:
                await self.comfy.cancel(pid)
            except httpx.HTTPError:
                pass

    async def _wait_prompt(self, job: Job, pid: str, deadline: float, label: str) -> dict | None:
        misses = 0
        down_since: float | None = None
        last_progress = None
        while True:
            if job.id in self._cancel:
                await self._safe_cancel(job)
                return None
            if time.monotonic() > deadline:
                raise _JobTimeout()
            try:
                entry = await self.comfy.history(pid)
                if entry is not None:
                    completed, failed, msg = history_status(entry)
                    if failed:
                        raise ComfyError(msg)
                    if completed:
                        return entry
                    state = "running"
                else:
                    state = await self.comfy.queue_state(pid)
                down_since = None
            except httpx.HTTPError as e:
                down_since = down_since or time.monotonic()
                if time.monotonic() - down_since > 120:
                    raise ComfyError(f"lost contact with ComfyUI: {e}") from e
                state = "unreachable"

            if state is None:
                misses += 1
                if misses >= 5:
                    raise ComfyError("ComfyUI no longer knows this job (was it restarted or the queue cleared?)")
            else:
                misses = 0
            progress = {"pending": "waiting in ComfyUI queue", "running": "generating",
                        "unreachable": "ComfyUI unreachable, retrying"}.get(state or "", last_progress)
            if label and progress:
                progress = f"{progress} ({label})"
            if progress != last_progress:
                job.progress = progress
                self.db.save(job)
                last_progress = progress
            await asyncio.sleep(self.poll_interval)


class _JobTimeout(Exception):
    pass


def _set(lst: list, i: int, value: Any) -> None:
    while len(lst) <= i:
        lst.append(None)
    lst[i] = value
