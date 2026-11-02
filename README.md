# HearthLight 🦎

Your own AI, running at home: a local AI gateway plus an Android chat app.

Use the models on your own PC (LM Studio for chat, ComfyUI for images) from your phone or
any web app, through **one authenticated, OpenAI-compatible API**.

```
 Android app / web app / any OpenAI SDK
                │  HTTPS (Tailscale or Cloudflare Tunnel), Bearer API key
                ▼
        Local AI Gateway  (this repo, runs on the PC, 127.0.0.1:8787)
          │                               │
          ▼                               ▼
   LM Studio :1234/v1               ComfyUI :8188
   (chat, embeddings)               (image workflows, via a job queue)
```

## What's inside

| Folder | What it is |
|---|---|
| [`gateway/`](gateway) | Python (FastAPI) service. OpenAI-compatible `/v1/chat/completions` (streaming), `/v1/models`, `/v1/embeddings`, `/v1/images/generations`, plus an image **job queue** API that survives phone sleep, network drops and gateway restarts. |
| [`android/`](android) | Kotlin + Jetpack Compose app: ChatGPT/Claude-style chat with streaming, Markdown and code blocks, model picker, reasoning ("thinking") display, chat history on the phone, image generation with workflow picker, gallery, save and share. |
| [`docs/`](docs) | Setup and reference docs. |

## Quick start

1. **PC:** install and start the gateway → [docs/setup-windows.md](docs/setup-windows.md)
2. **Remote access:** Tailscale (recommended) or Cloudflare Tunnel → [docs/remote-access.md](docs/remote-access.md)
3. **Phone:** install the APK and enter the URL and key → [docs/android.md](docs/android.md)
4. **Web apps / scripts:** call it like the OpenAI API → [docs/api.md](docs/api.md)
5. **Your own ComfyUI workflows:** export with *Export (API)* and drop them in `gateway/workflows/` → [docs/workflows.md](docs/workflows.md)

## Security model (short version)

- The gateway listens on `127.0.0.1` only. Nothing is exposed until *you* put Tailscale Serve or a Cloudflare Tunnel in front of it.
- Every API call needs `Authorization: Bearer <key>`. Keys are random 256-bit tokens, compared in constant time. Use one key per device so you can revoke one.
- Image links are HMAC-signed and expire, so `<img src>` works in browsers without leaking your key.
- **Never port-forward 8787 (or LM Studio's 1234 / ComfyUI's 8188) on your router.** LM Studio and ComfyUI have no auth of their own.

## Status

See [docs/verification.md](docs/verification.md) for exactly what was tested and what still needs a real PC.

Planned next: cloud GPU fallback (design in [docs/cloud-fallback.md](docs/cloud-fallback.md)), image input (vision / img2img), wake-on-LAN.
