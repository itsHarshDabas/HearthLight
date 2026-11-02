import json
import time

import httpx
import pytest

from conftest import KEY, make_settings
from fakes import PNG, ServerThread
from gateway.app import create_app


def test_health_is_public(gateway):
    r = httpx.get(gateway.url + "/health")
    assert r.status_code == 200 and r.json()["ok"] is True


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic abc"}])
def test_auth_required(gateway, headers):
    for method, path in [("GET", "/v1/models"), ("POST", "/v1/chat/completions"), ("GET", "/v1/images/jobs"),
                         ("GET", "/v1/workflows"), ("GET", "/v1/status")]:
        r = httpx.request(method, gateway.url + path, headers=headers, json={})
        assert r.status_code == 401, (path, r.text)
        assert r.json()["error"]["code"] == "invalid_api_key"


def test_x_api_key_header(gateway):
    r = httpx.get(gateway.url + "/v1/models", headers={"X-API-Key": KEY})
    assert r.status_code == 200


def test_models(client):
    r = client.get("/v1/models")
    ids = [m["id"] for m in r.json()["data"]]
    assert "qwen3-8b" in ids and all(m["upstream"] == "lmstudio" for m in r.json()["data"])


def test_chat_non_stream(client):
    r = client.post("/v1/chat/completions", json={"model": "qwen3-8b", "messages": [{"role": "user", "content": "hi there"}]})
    assert r.status_code == 200
    assert r.json()["choices"][0]["message"]["content"] == "Echo: hi there"


def test_chat_default_model(client, lmstudio):
    r = client.post("/v1/chat/completions", json={"messages": [{"role": "user", "content": "x"}]})
    assert r.status_code == 200
    assert lmstudio.server.config.app.state.requests[-1]["model"] == "qwen3-8b"


def test_chat_stream_is_incremental(client):
    t0 = time.monotonic()
    first_at = None
    chunks = []
    with client.stream("POST", "/v1/chat/completions",
                       json={"model": "qwen3-8b", "stream": True, "messages": [{"role": "user", "content": "one two three"}]}) as r:
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/event-stream")
        for line in r.iter_lines():
            if not line.startswith("data: "):
                continue
            if first_at is None:
                first_at = time.monotonic() - t0
            chunks.append(line[6:])
    total = time.monotonic() - t0
    assert chunks[-1] == "[DONE]"
    text = "".join(json.loads(c)["choices"][0]["delta"].get("content", "") for c in chunks[:-1])
    assert text == "Echo: one two three"
    assert first_at < total * 0.7  # tokens arrive before the stream ends (not buffered)


def test_chat_stream_reasoning_passthrough(client):
    with client.stream("POST", "/v1/chat/completions",
                       json={"model": "deepthink-r1", "stream": True, "messages": [{"role": "user", "content": "q"}]}) as r:
        body = r.read().decode()
    assert "reasoning_content" in body


def test_client_disconnect_stops_upstream(client, lmstudio):
    state = lmstudio.server.config.app.state
    before = state.disconnected
    with client.stream("POST", "/v1/chat/completions",
                       json={"model": "qwen3-8b", "stream": True, "messages": [{"role": "user", "content": "LONG"}]}) as r:
        for i, _ in enumerate(r.iter_lines()):
            if i > 5:
                break
    deadline = time.time() + 5
    while state.disconnected == before and time.time() < deadline:
        time.sleep(0.05)
    assert state.disconnected == before + 1


def test_upstream_error_passthrough(client):
    r = client.post("/v1/chat/completions", json={"model": "nope", "messages": [{"role": "user", "content": "x"}]})
    assert r.status_code == 404 and "not found" in r.text


def test_bad_bodies(client):
    assert client.post("/v1/chat/completions", content=b"not json", headers={"Content-Type": "application/json"}).status_code == 400
    assert client.post("/v1/chat/completions", json={"model": "qwen3-8b", "messages": []}).status_code == 400


def test_embeddings(client):
    r = client.post("/v1/embeddings", json={"model": "text-embedding-nomic-embed-text-v1.5", "input": "hi"})
    assert r.status_code == 200 and r.json()["data"][0]["embedding"] == [0.1, 0.2, 0.3]


def test_upstream_down_gives_503(tmp_path, comfy, workflows_dir):
    from fakes import free_port
    s = make_settings(tmp_path, f"http://127.0.0.1:{free_port()}", comfy.url, workflows_dir)
    with ServerThread(create_app(s)) as gw:
        r = httpx.post(gw.url + "/v1/chat/completions", headers={"Authorization": f"Bearer {KEY}"},
                       json={"model": "m", "messages": [{"role": "user", "content": "x"}]})
        assert r.status_code == 503 and "LM Studio" in r.json()["error"]["message"]
        st = httpx.get(gw.url + "/v1/status", headers={"Authorization": f"Bearer {KEY}"}).json()
        assert st["chat_upstreams"][0]["status"].startswith("unreachable")
        assert st["comfyui"]["status"] == "ok"


def test_fallback_to_second_upstream(tmp_path, lmstudio, comfy, workflows_dir):
    from fakes import free_port
    from gateway.config import ChatUpstream
    s = make_settings(tmp_path, f"http://127.0.0.1:{free_port()}", comfy.url, workflows_dir)
    s.chat_upstreams.append(ChatUpstream(name="backup", base_url=lmstudio.url + "/v1"))
    with ServerThread(create_app(s)) as gw:
        h = {"Authorization": f"Bearer {KEY}"}
        models = httpx.get(gw.url + "/v1/models", headers=h).json()["data"]
        assert models and all(m["upstream"] == "backup" for m in models)
        r = httpx.post(gw.url + "/v1/chat/completions", headers=h,
                       json={"model": "qwen3-8b", "messages": [{"role": "user", "content": "hey"}]})
        assert r.status_code == 200


def test_cors(tmp_path, lmstudio, comfy, workflows_dir):
    s = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir, GATEWAY_CORS_ORIGINS="http://localhost:5173")
    with ServerThread(create_app(s)) as gw:
        r = httpx.options(gw.url + "/v1/chat/completions", headers={
            "Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization,content-type"})
        assert r.status_code == 200
        assert r.headers["access-control-allow-origin"] == "http://localhost:5173"
        r = httpx.options(gw.url + "/v1/chat/completions", headers={
            "Origin": "http://evil.example", "Access-Control-Request-Method": "POST"})
        assert "access-control-allow-origin" not in r.headers


def test_status(client):
    st = client.get("/v1/status").json()
    assert st["chat_upstreams"][0]["status"] == "ok"
    assert st["comfyui"]["devices"][0]["name"].endswith("4090")
