"""OpenAI-compatible chat upstreams (LM Studio, Ollama, llama.cpp, vLLM, a cloud endpoint...).

Upstreams are tried in the order they are configured. A request for a model is
sent to the first reachable upstream that serves that model; if that upstream
is unreachable, the next one serving the same model is tried. This is the hook
for a future cloud fallback: add a second upstream that serves the same model
names and it takes over when the PC-local one is down.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

from .config import ChatUpstream

log = logging.getLogger("gateway.upstreams")

MODEL_CACHE_TTL = 30.0


@dataclass
class ModelEntry:
    id: str
    upstream: ChatUpstream
    raw: dict


class UpstreamUnavailable(Exception):
    pass


class ChatRouter:
    def __init__(self, upstreams: list[ChatUpstream], client: httpx.AsyncClient):
        self.upstreams = upstreams
        self.client = client
        self._cache: list[ModelEntry] = []
        self._cache_at = 0.0
        self._lock = asyncio.Lock()
        self.last_status: dict[str, str] = {}

    @staticmethod
    def headers(up: ChatUpstream) -> dict[str, str]:
        h = {"Accept": "application/json"}
        if up.api_key:
            h["Authorization"] = f"Bearer {up.api_key}"
        return h

    async def _fetch_models(self, up: ChatUpstream) -> list[dict]:
        r = await self.client.get(f"{up.base_url}/models", headers=self.headers(up), timeout=10.0)
        r.raise_for_status()
        data = r.json()
        return data.get("data", []) if isinstance(data, dict) else []

    async def models(self, refresh: bool = False) -> list[ModelEntry]:
        async with self._lock:
            if not refresh and self._cache and time.monotonic() - self._cache_at < MODEL_CACHE_TTL:
                return self._cache
            results = await asyncio.gather(*(self._fetch_models(u) for u in self.upstreams), return_exceptions=True)
            entries: list[ModelEntry] = []
            for up, res in zip(self.upstreams, results):
                if isinstance(res, BaseException):
                    self.last_status[up.name] = f"unreachable: {type(res).__name__}: {res}"
                    log.warning("upstream %s: cannot list models: %s", up.name, res)
                    continue
                self.last_status[up.name] = "ok"
                for m in res:
                    mid = m.get("id")
                    if not mid:
                        continue
                    entries.append(ModelEntry(mid, up, m))
            self._cache = entries
            self._cache_at = time.monotonic()
            return entries

    async def candidates(self, model: str | None) -> list[ChatUpstream]:
        """Upstreams to try for `model`, in priority order."""
        entries = await self.models()
        if model:
            ups = [e.upstream for e in entries if e.id == model]
            if not ups:
                entries = await self.models(refresh=True)
                ups = [e.upstream for e in entries if e.id == model]
            if ups:
                # keep config priority order, de-duplicated
                return [u for u in self.upstreams if u in ups]
            # Unknown model: still let the first upstream try (it may JIT-load it,
            # e.g. LM Studio / Ollama), so the user gets the upstream's own error.
        reachable = [u for u in self.upstreams if self.last_status.get(u.name) == "ok"]
        return reachable or list(self.upstreams)

    async def default_model(self, configured: str) -> str | None:
        if configured:
            return configured
        entries = await self.models()
        for e in entries:
            if "embed" not in e.id.lower():
                return e.id
        return None
