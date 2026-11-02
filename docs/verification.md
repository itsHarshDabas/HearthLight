# What has been verified

Honest status of this first version (built in a cloud sandbox without a GPU, an Android SDK or your PC).

## Verified automatically

**Gateway** (`gateway/tests`, 36 tests, run on every push in CI) against fake LM Studio and ComfyUI
servers that speak the same HTTP APIs, over real sockets:
- API-key auth on every route, `X-API-Key` alternative, constant-time compare, public `/health`.
- Chat passthrough, streaming that arrives incrementally (not buffered), `reasoning_content` passthrough,
  client disconnect stops upstream generation, upstream error passthrough, 503 when LM Studio is down,
  fallback to a second upstream, default model selection, CORS allow-list.
- Image jobs end to end: parameter injection into the graph (prompt, size rounding, steps clamp, seed),
  batch `n`, sequential `n` for workflows without batch, ComfyUI validation errors and execution errors
  surfaced, cancel while queued and while running, delete, signed URLs (valid and tampered),
  OpenAI `/v1/images/generations` with `url` and `b64_json`, and **jobs resuming after a gateway restart**.
- Workflow auto-detection on the bundled SDXL and Flux graphs, placeholders, meta files, UI-format rejection.

**Android shared client** (`android/core`, 14 Kotlin tests): SSE parsing, `<think>` splitting,
Markdown parsing, URL normalisation, error messages, chat storage, plus a live end-to-end run of
the Kotlin client against the real gateway process: streamed chat and a 2-image job, downloaded.

## Not yet verified

- **The Android UI on a real phone.** It compiles and the debug APK builds in CI, but nobody has tapped through it on a device yet.
- **Real LM Studio / ComfyUI.** The fakes follow their documented APIs (`/v1/*`, `/prompt`, `/history/{id}`,
  `/queue`, `/view`, `/interrupt`, `/system_stats`), but behaviour on your actual versions, custom nodes and
  GPU hasn't been observed.
- **Windows scripts** (`setup.ps1`, `start.bat`) haven't been run on Windows.
- Tailscale Serve and Cloudflare Tunnel steps follow their current docs but weren't run here.

## Smoke test on your PC

```powershell
cd gateway; scripts\start.bat
# new terminal
$K = .venv\Scripts\local-ai-gateway --show-key
curl http://127.0.0.1:8787/v1/status -H "Authorization: Bearer $K"
curl http://127.0.0.1:8787/v1/chat/completions -H "Authorization: Bearer $K" -H "Content-Type: application/json" `
  -d '{"messages":[{"role":"user","content":"Say hi"}]}'
curl http://127.0.0.1:8787/v1/images/jobs -H "Authorization: Bearer $K" -H "Content-Type: application/json" `
  -d '{"prompt":"a red fox","workflow":"sdxl-basic"}'
```

No GPU handy? `python dev/run_fakes.py` starts fake LM Studio and ComfyUI on the default ports.
