"""Chat attachments: speech-to-text for voice notes and text extraction for documents.

Images need no help from the gateway: the app sends them as OpenAI-style
`image_url` content parts and they pass straight through to LM Studio, which
handles them when a vision model (Qwen2.5-VL, Gemma 3, LLaVA...) is loaded.

Audio transcription backends, chosen by `[audio] backend`:
  * "faster-whisper": runs Whisper locally in the gateway process
    (`pip install -e ".[audio]"`). The model downloads once on first use.
  * "openai": forwards to any OpenAI-compatible /audio/transcriptions server,
    e.g. a whisper.cpp server, speaches, or a hosted API.
  * "auto" (default): faster-whisper if installed, else "openai" if
    `upstream_url` is set, else off.
  * "off": transcription disabled; the app says so and keeps the audio out of the prompt.
"""

from __future__ import annotations

import asyncio
import io
import logging
import re
import zipfile
from dataclasses import dataclass
from typing import Any

import httpx

from .config import AudioConfig

log = logging.getLogger("gateway.attachments")


class AttachmentError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------- audio


def _has_faster_whisper() -> bool:
    try:
        import faster_whisper  # noqa: F401
    except Exception:  # ImportError, or a broken CUDA/ctranslate2 install
        return False
    return True


class Transcriber:
    def __init__(self, cfg: AudioConfig, http: httpx.AsyncClient):
        self.cfg = cfg
        self.http = http
        self._model: Any = None
        self._load_error: str | None = None
        self._lock = asyncio.Lock()
        self.backend = self._resolve_backend()

    def _resolve_backend(self) -> str:
        b = (self.cfg.backend or "auto").lower()
        if b == "auto":
            if _has_faster_whisper():
                return "faster-whisper"
            return "openai" if self.cfg.upstream_url else "off"
        if b == "faster-whisper" and not _has_faster_whisper():
            log.warning("[audio] backend = faster-whisper but it isn't installed; run: pip install -e \".[audio]\"")
            return "unavailable"
        if b == "openai" and not self.cfg.upstream_url:
            log.warning("[audio] backend = openai needs upstream_url")
            return "unavailable"
        return b if b in ("faster-whisper", "openai", "off") else "off"

    @property
    def enabled(self) -> bool:
        return self.backend in ("faster-whisper", "openai")

    def info(self) -> dict:
        out: dict[str, Any] = {"enabled": self.enabled, "backend": self.backend}
        if self.backend == "faster-whisper":
            out["model"] = self.cfg.model
            out["loaded"] = self._model is not None
            if self._load_error:
                out["error"] = self._load_error
        if not self.enabled:
            out["hint"] = 'Install local Whisper with: pip install -e ".[audio]"  (see docs/attachments.md)'
        return out

    async def transcribe(self, data: bytes, filename: str, language: str | None = None, prompt: str | None = None) -> dict:
        if not self.enabled:
            raise AttachmentError(501, "Speech-to-text isn't set up on the gateway. "
                                       'Install it on the PC with: pip install -e ".[audio]" (see docs/attachments.md).')
        if not data:
            raise AttachmentError(400, "audio file is empty")
        if len(data) > self.cfg.max_upload_mb * 1024 * 1024:
            raise AttachmentError(413, f"audio file is larger than {self.cfg.max_upload_mb} MB")
        language = language or self.cfg.language or None
        if self.backend == "openai":
            return await self._transcribe_upstream(data, filename, language, prompt)
        return await self._transcribe_local(data, language, prompt)

    async def _transcribe_upstream(self, data: bytes, filename: str, language: str | None, prompt: str | None) -> dict:
        url = self.cfg.upstream_url.rstrip("/") + "/audio/transcriptions"
        form = {"model": self.cfg.upstream_model or "whisper-1", "response_format": "json"}
        if language:
            form["language"] = language
        if prompt:
            form["prompt"] = prompt
        headers = {"Authorization": f"Bearer {self.cfg.upstream_api_key}"} if self.cfg.upstream_api_key else {}
        try:
            r = await self.http.post(url, data=form, files={"file": (filename or "audio.m4a", data)},
                                     headers=headers, timeout=httpx.Timeout(30.0, read=600.0))
        except httpx.HTTPError as e:
            raise AttachmentError(503, f"speech-to-text server unreachable: {e}") from e
        if r.status_code != 200:
            raise AttachmentError(502, f"speech-to-text server said {r.status_code}: {r.text[:300]}")
        try:
            body = r.json()
        except ValueError:
            return {"text": r.text.strip()}
        return {"text": str(body.get("text", "")).strip(), **{k: body[k] for k in ("language", "duration") if k in body}}

    def _load(self):
        from faster_whisper import WhisperModel

        device = self.cfg.device or "auto"
        compute = self.cfg.compute_type or "default"
        log.info("loading Whisper model %s (device=%s, compute=%s)...", self.cfg.model, device, compute)
        try:
            return WhisperModel(self.cfg.model, device=device, compute_type=compute)
        except Exception as e:  # CUDA libraries missing etc.: fall back to CPU
            if device == "cpu":
                raise
            log.warning("Whisper on %s failed (%s); falling back to CPU", device, e)
            return WhisperModel(self.cfg.model, device="cpu", compute_type="int8")

    async def _transcribe_local(self, data: bytes, language: str | None, prompt: str | None) -> dict:
        async with self._lock:  # one transcription at a time; Whisper already uses all cores / the GPU
            if self._model is None:
                try:
                    self._model = await asyncio.to_thread(self._load)
                    self._load_error = None
                except Exception as e:
                    self._load_error = str(e)
                    raise AttachmentError(500, f"couldn't load Whisper model '{self.cfg.model}': {e}") from e

            def run() -> dict:
                segments, info = self._model.transcribe(
                    io.BytesIO(data), language=language, initial_prompt=prompt, vad_filter=True, beam_size=5,
                )
                text = "".join(s.text for s in segments).strip()
                return {"text": text, "language": info.language, "duration": round(info.duration, 2)}

            try:
                return await asyncio.to_thread(run)
            except Exception as e:
                gpu_problem = any(k in str(e).lower() for k in ("cuda", "cublas", "cudnn"))
                if not gpu_problem or self.cfg.device == "cpu":
                    raise AttachmentError(400, f"couldn't decode or transcribe the audio: {e}") from e
                # Common on Windows: the GPU is found but the CUDA 12 libraries aren't installed.
                log.warning("Whisper failed on the GPU (%s); switching to CPU", e)
                from faster_whisper import WhisperModel

                self._model = await asyncio.to_thread(WhisperModel, self.cfg.model, device="cpu", compute_type="int8")
                try:
                    return await asyncio.to_thread(run)
                except Exception as e2:
                    raise AttachmentError(400, f"couldn't decode or transcribe the audio: {e2}") from e2


# ---------------------------------------------------------------- documents

TEXT_EXTENSIONS = {
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "log",
    "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp",
    "hpp", "cs", "go", "rs", "rb", "php", "swift", "sh", "bat", "ps1", "sql", "gradle", "properties", "tex",
    "srt", "vtt", "env", "gitignore", "dockerfile", "r", "lua", "dart", "scala", "vue", "svelte",
}


@dataclass
class Extracted:
    text: str
    kind: str
    pages: int | None = None
    truncated: bool = False

    def public(self, filename: str) -> dict:
        out: dict[str, Any] = {"object": "attachment.text", "filename": filename, "kind": self.kind,
                               "text": self.text, "chars": len(self.text), "truncated": self.truncated}
        if self.pages is not None:
            out["pages"] = self.pages
        return out


def _ext(filename: str) -> str:
    name = filename.lower().rsplit("/", 1)[-1]
    return name.rsplit(".", 1)[-1] if "." in name else name


def _decode_text(data: bytes) -> str:
    if data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return data.decode("utf-16", "replace")
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("latin-1")


def _looks_binary(data: bytes) -> bool:
    sample = data[:4096]
    return b"\x00" in sample and sample[:2] not in (b"\xff\xfe", b"\xfe\xff")


def _pdf_text(data: bytes) -> tuple[str, int]:
    try:
        from pypdf import PdfReader
    except ImportError as e:
        raise AttachmentError(501, "PDF support needs pypdf: pip install pypdf") from e
    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            try:
                reader.decrypt("")
            except Exception as e:
                raise AttachmentError(400, "this PDF is password-protected") from e
        pages = []
        for i, page in enumerate(reader.pages, 1):
            t = (page.extract_text() or "").strip()
            if t:
                pages.append(f"[page {i}]\n{t}")
        return "\n\n".join(pages), len(reader.pages)
    except AttachmentError:
        raise
    except Exception as e:
        raise AttachmentError(400, f"couldn't read this PDF: {e}") from e


def _office_xml_text(data: bytes, members: list[str]) -> str:
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:
        raise AttachmentError(400, "file is not a valid Office document") from e
    out = []
    for name in members:
        if name not in z.namelist():
            continue
        xml = z.read(name).decode("utf-8", "replace")
        xml = re.sub(r"</w:p>|</a:p>|<w:br/>|<w:tab/>", "\n", xml)
        text = re.sub(r"<[^>]+>", "", xml)
        out.append(_unescape(text))
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


def _unescape(s: str) -> str:
    import html

    return html.unescape(s)


def extract_text(filename: str, content_type: str | None, data: bytes, max_chars: int) -> Extracted:
    if not data:
        raise AttachmentError(400, "file is empty")
    ext = _ext(filename)
    ctype = (content_type or "").lower()
    pages = None
    if ext == "pdf" or ctype == "application/pdf" or data[:5] == b"%PDF-":
        text, pages = _pdf_text(data)
        kind = "pdf"
        if not text.strip():
            raise AttachmentError(422, "this PDF has no text layer (it's probably scanned). "
                                       "Send pages as photos to a vision model instead.")
    elif ext == "docx":
        text, kind = _office_xml_text(data, ["word/document.xml"]), "docx"
    elif ext == "pptx":
        z = zipfile.ZipFile(io.BytesIO(data)) if zipfile.is_zipfile(io.BytesIO(data)) else None
        slides = sorted((n for n in (z.namelist() if z else []) if re.match(r"ppt/slides/slide\d+\.xml$", n)),
                        key=lambda n: int(re.findall(r"\d+", n)[-1]))
        text, kind = _office_xml_text(data, slides), "pptx"
    elif ext in TEXT_EXTENSIONS or ctype.startswith("text/") or ctype in (
            "application/json", "application/xml", "application/x-yaml", "application/javascript") \
            or not _looks_binary(data):
        text, kind = _decode_text(data), "text"
    else:
        raise AttachmentError(415, f"can't read text from '{filename}' ({content_type or 'unknown type'}). "
                                   "Supported: text/code files, PDF, DOCX, PPTX.")
    truncated = len(text) > max_chars
    if truncated:
        text = text[:max_chars]
    return Extracted(text=text, kind=kind, pages=pages, truncated=truncated)
