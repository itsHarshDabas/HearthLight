# Cloud GPU fallback (design, not built yet)

Goal: same app, same API. When the PC is off or busy, requests go to a cloud GPU.

## Chat: already pluggable

`[[chat.upstreams]]` is an ordered list. A request goes to the first reachable upstream that
serves the requested model, and the next one is tried if the connection fails. So a fallback is
mostly configuration: add an OpenAI-compatible endpoint that serves the **same model ids**, e.g.
a RunPod Serverless vLLM worker, Modal, or any hosted OpenAI-compatible provider:

```toml
[[chat.upstreams]]
name = "lmstudio"
base_url = "http://127.0.0.1:1234/v1"

[[chat.upstreams]]
name = "runpod"
base_url = "https://api.runpod.ai/v2/<endpoint-id>/openai/v1"
api_key = "..."
```

Still to do: a model-alias map (local `qwen3-8b` ↔ cloud `Qwen/Qwen3-8B`), a "local only" flag
per request, and cost limits.

## Images: needs an `ImageBackend` interface

Today `JobManager` talks to one `ComfyClient`. The plan:

1. Extract an interface: `submit(graph) → handle`, `poll(handle) → status/images`, `cancel(handle)`. `ComfyClient` is the first implementation.
2. Add a `RunPodComfyBackend` for a serverless ComfyUI worker (e.g. `runpod-workers/worker-comfyui`), which accepts the same API-format workflow JSON and returns images. The workflows folder is reused unchanged; the cloud image needs the same models and custom nodes.
3. Routing per job: `local` → `cloud` if ComfyUI is unreachable or the queue is longer than N. Store the backend name on the job so restarts resume on the right one.

## Where the gateway runs when the PC is off

Fallback only helps if the gateway itself is up. Two options:
- **Small always-on host** (a Raspberry Pi, or a free-tier VM on your tailnet) runs the gateway. LM Studio/ComfyUI on the PC become upstreams reached over Tailscale, and the cloud is the next upstream. Add wake-on-LAN so the gateway can wake the PC first.
- **Keep the gateway on the PC** and accept that "PC off" means "use the cloud provider directly". Simpler, and the app can hold two server profiles.

The first is the long-term design. Nothing in the API changes for clients.
