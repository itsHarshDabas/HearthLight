"""Minimal async ComfyUI HTTP client."""

from __future__ import annotations

from typing import Any

import httpx


class ComfyError(Exception):
    pass


class ComfyClient:
    def __init__(self, base_url: str, client: httpx.AsyncClient):
        self.base = base_url.rstrip("/")
        self.http = client

    async def submit(self, graph: dict, client_id: str) -> str:
        try:
            r = await self.http.post(f"{self.base}/prompt", json={"prompt": graph, "client_id": client_id}, timeout=30.0)
        except httpx.HTTPError as e:
            raise ComfyError(f"ComfyUI unreachable at {self.base}: {e}") from e
        if r.status_code != 200:
            raise ComfyError(_describe_error(r))
        data = r.json()
        if data.get("node_errors"):
            raise ComfyError(f"ComfyUI rejected the workflow: {data['node_errors']}")
        pid = data.get("prompt_id")
        if not pid:
            raise ComfyError(f"ComfyUI returned no prompt_id: {data}")
        return pid

    async def history(self, prompt_id: str) -> dict | None:
        r = await self.http.get(f"{self.base}/history/{prompt_id}", timeout=15.0)
        r.raise_for_status()
        data = r.json()
        return data.get(prompt_id) if isinstance(data, dict) else None

    async def queue(self) -> dict[str, Any]:
        r = await self.http.get(f"{self.base}/queue", timeout=10.0)
        r.raise_for_status()
        return r.json()

    async def queue_state(self, prompt_id: str) -> str | None:
        """'running', 'pending' or None if ComfyUI doesn't know the prompt."""
        q = await self.queue()
        for item in q.get("queue_running", []):
            if len(item) > 1 and item[1] == prompt_id:
                return "running"
        for item in q.get("queue_pending", []):
            if len(item) > 1 and item[1] == prompt_id:
                return "pending"
        return None

    async def view(self, filename: str, subfolder: str, type_: str) -> bytes:
        r = await self.http.get(
            f"{self.base}/view", params={"filename": filename, "subfolder": subfolder, "type": type_}, timeout=60.0
        )
        r.raise_for_status()
        return r.content

    async def cancel(self, prompt_id: str) -> None:
        state = await self.queue_state(prompt_id)
        if state == "pending":
            await self.http.post(f"{self.base}/queue", json={"delete": [prompt_id]}, timeout=10.0)
        elif state == "running":
            # Newer ComfyUI only interrupts the given prompt; older ones interrupt whatever runs,
            # which is this prompt since we checked it is the running one.
            await self.http.post(f"{self.base}/interrupt", json={"prompt_id": prompt_id}, timeout=10.0)

    async def system_stats(self) -> dict:
        r = await self.http.get(f"{self.base}/system_stats", timeout=5.0)
        r.raise_for_status()
        return r.json()

    async def checkpoints(self) -> list[str]:
        r = await self.http.get(f"{self.base}/object_info/CheckpointLoaderSimple", timeout=10.0)
        r.raise_for_status()
        try:
            return list(r.json()["CheckpointLoaderSimple"]["input"]["required"]["ckpt_name"][0])
        except (KeyError, IndexError, TypeError):
            return []


def _describe_error(r: httpx.Response) -> str:
    try:
        data = r.json()
    except ValueError:
        return f"ComfyUI HTTP {r.status_code}: {r.text[:500]}"
    err = data.get("error")
    msg = err.get("message") if isinstance(err, dict) else str(err or "")
    details = err.get("details") if isinstance(err, dict) else ""
    node_errors = data.get("node_errors") or {}
    parts = [f"ComfyUI HTTP {r.status_code}: {msg}"]
    if details:
        parts.append(str(details))
    for nid, ne in node_errors.items():
        for e in ne.get("errors", []):
            parts.append(f"node {nid} ({ne.get('class_type')}): {e.get('message')} {e.get('details', '')}".strip())
    return " | ".join(parts)


def output_images(history_entry: dict) -> list[dict]:
    """Images listed in a /history entry. Prefers saved ('output') images over previews."""
    imgs: list[dict] = []
    for node_out in (history_entry.get("outputs") or {}).values():
        for img in node_out.get("images", []) or []:
            if isinstance(img, dict) and img.get("filename"):
                imgs.append(img)
    saved = [i for i in imgs if i.get("type") == "output"]
    return saved or imgs


def history_status(history_entry: dict) -> tuple[bool, bool, str]:
    """(completed, failed, message)."""
    status = history_entry.get("status") or {}
    status_str = status.get("status_str")
    messages = status.get("messages") or []
    if status_str == "error":
        detail = ""
        for m in messages:
            if isinstance(m, list) and len(m) == 2 and m[0] == "execution_error":
                d = m[1] or {}
                detail = f"{d.get('node_type', '')}: {d.get('exception_message', '')}".strip(": ")
        return True, True, detail or "ComfyUI reported an execution error"
    if any(isinstance(m, list) and m and m[0] == "execution_interrupted" for m in messages):
        return True, True, "interrupted"
    completed = bool(status.get("completed")) or (status_str == "success") or (not status and bool(history_entry.get("outputs")))
    return completed, False, ""
