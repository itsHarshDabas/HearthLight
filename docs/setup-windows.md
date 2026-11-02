# Setting up the gateway on Windows

You need: **Python 3.11+** ([python.org](https://www.python.org/downloads/), tick *Add to PATH*),
**LM Studio** and/or **ComfyUI** already working on the PC.

## 1. Start the model servers

**LM Studio:** open the *Developer* tab → *Start Server* (default `http://127.0.0.1:1234`).
Load a model, or enable *Just-in-time model loading* so the gateway can ask for any downloaded model.
Leave "Serve on Local Network" **off**; the gateway talks to it on localhost.

**ComfyUI:** start it normally (default `http://127.0.0.1:8188`). Don't use `--listen`.

Ollama works too: add it as a second upstream in `config.toml` (`http://127.0.0.1:11434/v1`).

## 2. Install the gateway

```powershell
cd path\to\repo\gateway
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
```

This creates `.venv`, installs the gateway, copies `config.example.toml` to `config.toml` and
prints your **API key** (also stored in `gateway\data\api_key.txt`). Keep it secret.

## 3. Run it

Double-click `scripts\start.bat` (or run it in a terminal). Check it:

```powershell
curl http://127.0.0.1:8787/health
curl http://127.0.0.1:8787/v1/status -H "Authorization: Bearer YOUR_KEY"
```

`/v1/status` shows whether LM Studio and ComfyUI are reachable and your GPU's free VRAM.
Interactive API docs: <http://127.0.0.1:8787/docs>.

## 4. Edit your workflows

The two example workflows in `gateway\workflows\` name checkpoints (`sd_xl_base_1.0.safetensors`,
`flux1-dev-fp8.safetensors`). Change `ckpt_name` to models you actually have, or export your own
workflows (see [workflows.md](workflows.md)). Workflow files are re-read automatically; no restart needed.

## 5. Start it automatically (optional)

Task Scheduler → *Create Task*:
- *General:* "Run only when user is logged on" (simplest; LM Studio and ComfyUI run in your session anyway).
- *Triggers:* At log on.
- *Actions:* Start a program → `C:\path\to\repo\gateway\scripts\start.bat`, *Start in* `C:\path\to\repo\gateway`.

Also make LM Studio start its server on launch (Settings → *Start LLM server on app launch*) and add ComfyUI to startup.

## Keys

- Show the current key: `.venv\Scripts\local-ai-gateway --show-key`
- Make keys per device: `.venv\Scripts\local-ai-gateway --new-key`, then list them in `config.toml`:
  ```toml
  [auth]
  api_keys = ["sk-local-phone...", "sk-local-webapp..."]
  ```
  Once `api_keys` is set, the auto-generated key in `data\api_key.txt` is no longer accepted. Remove a key and restart to revoke it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `503 No chat upstream reachable` | LM Studio's server isn't running, or it's on another port (`[[chat.upstreams]] base_url`). |
| Model list empty | Load a model in LM Studio or enable JIT loading. |
| Image job fails with `Value not in list: ckpt_name` | The workflow names a checkpoint you don't have. Edit it or pass `"checkpoint"`. |
| Image job fails with `CUDA out of memory` | Lower the size or unload the LM Studio model while generating. Both share the GPU. |
| Image URLs point to `127.0.0.1` from the phone | Set `public_base_url` in `config.toml` to your Tailscale/Cloudflare URL. The app doesn't need this; it uses relative paths. |
