import io
import sys
import types
import zipfile

import httpx
import pytest
from fastapi import FastAPI, File, Form, UploadFile

from conftest import KEY, make_settings
from fakes import PNG, ServerThread
from gateway.app import create_app


def fake_whisper_server() -> FastAPI:
    app = FastAPI()
    app.state.calls = []

    @app.post("/v1/audio/transcriptions")
    async def tr(file: UploadFile = File(...), model: str = Form(...), language: str | None = Form(None)):
        data = await file.read()
        app.state.calls.append({"filename": file.filename, "size": len(data), "model": model, "language": language})
        return {"text": f" heard {len(data)} bytes ", "language": language or "en"}

    return app


@pytest.fixture(scope="module")
def whisper():
    with ServerThread(fake_whisper_server()) as s:
        yield s


def _gateway(tmp_path, lmstudio, comfy, workflows_dir, **env):
    settings = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir, **env)
    return ServerThread(create_app(settings))


def _client(gw):
    return httpx.Client(base_url=gw.url, headers={"Authorization": f"Bearer {KEY}"}, timeout=30)


def test_image_content_parts_pass_through(client, lmstudio):
    import base64
    url = "data:image/png;base64," + base64.b64encode(PNG).decode()
    msg = {"role": "user", "content": [{"type": "text", "text": "what is this"},
                                       {"type": "image_url", "image_url": {"url": url}}]}
    r = client.post("/v1/chat/completions", json={"model": "qwen3-8b", "messages": [msg]})
    assert r.status_code == 200, r.text
    assert r.json()["choices"][0]["message"]["content"] == "Echo: what is this"
    sent = lmstudio.server.config.app.state.requests[-1]["messages"][0]["content"]
    assert sent[1] == {"type": "image_url", "image_url": {"url": url}}


def test_transcription_off_gives_clear_501(tmp_path, lmstudio, comfy, workflows_dir, monkeypatch):
    with _gateway(tmp_path, lmstudio, comfy, workflows_dir, GATEWAY_AUDIO_BACKEND="off") as gw, _client(gw) as c:
        r = c.post("/v1/audio/transcriptions", files={"file": ("a.m4a", b"xxxx", "audio/mp4")}, data={"model": "whisper-1"})
        assert r.status_code == 501
        assert r.json()["error"]["code"] == "transcription_unavailable"
        assert "pip install" in r.json()["error"]["message"]
        assert c.get("/v1/status").json()["audio"]["enabled"] is False


def test_transcription_via_openai_upstream(tmp_path, lmstudio, comfy, workflows_dir, whisper):
    with _gateway(tmp_path, lmstudio, comfy, workflows_dir, GATEWAY_AUDIO_BACKEND="openai",
                  GATEWAY_AUDIO_URL=whisper.url + "/v1") as gw, _client(gw) as c:
        status = c.get("/v1/status").json()["audio"]
        assert status == {"enabled": True, "backend": "openai"}
        r = c.post("/v1/audio/transcriptions", files={"file": ("note.m4a", b"12345", "audio/mp4")},
                   data={"language": "hi"})
        assert r.status_code == 200, r.text
        assert r.json()["text"] == "heard 5 bytes"
        call = whisper.server.config.app.state.calls[-1]
        assert call == {"filename": "note.m4a", "size": 5, "model": "whisper-1", "language": "hi"}
        r = c.post("/v1/audio/transcriptions", files={"file": ("n.m4a", b"12", "audio/mp4")},
                   data={"response_format": "text"})
        assert r.text == "heard 2 bytes"


def test_transcription_local_faster_whisper(tmp_path, lmstudio, comfy, workflows_dir, monkeypatch):
    loaded = []

    class Seg:
        def __init__(self, t):
            self.text = t

    class FakeModel:
        def __init__(self, name, device, compute_type):
            loaded.append((name, device))

        def transcribe(self, f, **kw):
            assert f.read() == b"audio!"
            return iter([Seg(" hello"), Seg(" world")]), types.SimpleNamespace(language="en", duration=1.234)

    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=FakeModel))
    with _gateway(tmp_path, lmstudio, comfy, workflows_dir, GATEWAY_WHISPER_MODEL="tiny") as gw, _client(gw) as c:
        assert c.get("/v1/status").json()["audio"]["backend"] == "faster-whisper"
        for _ in range(2):
            r = c.post("/v1/audio/transcriptions", files={"file": ("a.wav", b"audio!", "audio/wav")})
            assert r.status_code == 200, r.text
            assert r.json() == {"text": "hello world", "language": "en", "duration": 1.23}
        assert loaded == [("tiny", "auto")]  # loaded once, lazily


def test_transcription_requires_key(gateway):
    r = httpx.post(gateway.url + "/v1/audio/transcriptions", files={"file": ("a.m4a", b"x")})
    assert r.status_code == 401


def test_extract_text_file(client):
    r = client.post("/v1/attachments/extract", files={"file": ("notes.md", "# Hi\nnamaste ✓".encode(), "text/markdown")})
    assert r.status_code == 200
    assert r.json()["text"] == "# Hi\nnamaste ✓" and r.json()["kind"] == "text"


def test_extract_docx(client):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("word/document.xml",
                   '<w:document><w:body><w:p><w:r><w:t>First &amp; line</w:t></w:r></w:p>'
                   '<w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>')
    r = client.post("/v1/attachments/extract", files={"file": ("a.docx", buf.getvalue(), "application/octet-stream")})
    assert r.status_code == 200, r.text
    assert r.json()["text"] == "First & line\nSecond"


def test_extract_pdf(client):
    pypdf = pytest.importorskip("pypdf")
    w = pypdf.PdfWriter()
    w.add_blank_page(100, 100)
    buf = io.BytesIO()
    w.write(buf)
    r = client.post("/v1/attachments/extract", files={"file": ("scan.pdf", buf.getvalue(), "application/pdf")})
    assert r.status_code == 422  # no text layer
    assert "scanned" in r.json()["error"]["message"]


def test_extract_rejects_binary(client):
    r = client.post("/v1/attachments/extract", files={"file": ("x.bin", b"\x00\x01\x02" * 100, "application/octet-stream")})
    assert r.status_code == 415


def test_extract_truncates(tmp_path, lmstudio, comfy, workflows_dir):
    settings = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir)
    settings.attachments.max_text_chars = 10
    with ServerThread(create_app(settings)) as gw, _client(gw) as c:
        r = c.post("/v1/attachments/extract", files={"file": ("a.txt", b"0123456789abcdef", "text/plain")})
        assert r.json()["text"] == "0123456789" and r.json()["truncated"] is True


def test_local_whisper_falls_back_to_cpu_on_cuda_error(tmp_path, lmstudio, comfy, workflows_dir, monkeypatch):
    devices = []

    class FakeModel:
        def __init__(self, name, device, compute_type):
            self.device = device
            devices.append(device)

        def transcribe(self, f, **kw):
            if self.device != "cpu":
                raise RuntimeError("Library cublas64_12.dll is not found or cannot be loaded")
            return iter([types.SimpleNamespace(text="ok")]), types.SimpleNamespace(language="en", duration=1.0)

    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=FakeModel))
    with _gateway(tmp_path, lmstudio, comfy, workflows_dir) as gw, _client(gw) as c:
        r = c.post("/v1/audio/transcriptions", files={"file": ("a.wav", b"x", "audio/wav")})
        assert r.status_code == 200, r.text
        assert r.json()["text"] == "ok"
        assert devices == ["auto", "cpu"]
