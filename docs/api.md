# API reference

Base URL: your Tailscale/Cloudflare URL (or `http://127.0.0.1:8787` on the PC).
Auth: `Authorization: Bearer <key>` (or `X-API-Key: <key>`) on every `/v1/*` call.
Errors use OpenAI's shape: `{"error": {"message": "...", "type": "...", "code": ...}}`.
Full interactive schema: `GET /docs` on the gateway.

## Chat (OpenAI-compatible)

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/models` | Models from all upstreams; each has `upstream`. |
| POST | `/v1/chat/completions` | Passed through to LM Studio. `stream: true` gives SSE. If `model` is missing, `default_model` (or the first loaded model) is used. |
| POST | `/v1/completions` | Legacy completions passthrough. |
| POST | `/v1/embeddings` | Passthrough. |

Any OpenAI SDK works by changing the base URL:

```python
from openai import OpenAI
client = OpenAI(base_url="https://my-pc.tail1234.ts.net/v1", api_key="sk-local-...")
for chunk in client.chat.completions.create(
        model="qwen3-8b", stream=True,
        messages=[{"role": "user", "content": "Hello!"}]):
    print(chunk.choices[0].delta.content or "", end="")
```

```js
// Browser or Node (fetch + streaming). Add your web app's origin to cors_origins in config.toml.
const res = await fetch(`${BASE}/v1/chat/completions`, {
  method: "POST",
  headers: { "Authorization": `Bearer ${KEY}`, "Content-Type": "application/json" },
  body: JSON.stringify({ model: "qwen3-8b", stream: true,
                         messages: [{ role: "user", content: "Hello!" }] }),
});
const reader = res.body.getReader(); const dec = new TextDecoder(); let buf = "";
while (true) {
  const { value, done } = await reader.read(); if (done) break;
  buf += dec.decode(value, { stream: true });
  const lines = buf.split("\n"); buf = lines.pop();
  for (const line of lines) {
    if (!line.startsWith("data: ") || line === "data: [DONE]") continue;
    const delta = JSON.parse(line.slice(6)).choices?.[0]?.delta;
    if (delta?.content) output.textContent += delta.content;
  }
}
```

> **Don't put a key in public front-end code.** For a public website, call the gateway from your
> own backend. Browser calls with a key are fine for private tools (a page on your tailnet, a local dev server).

Stopping a stream (closing the connection) stops generation in LM Studio too.

## Images

### Workflows

`GET /v1/workflows`:

```json
{"data": [{"id": "sdxl-basic", "title": "SDXL (basic)", "description": "...",
           "params": ["cfg","checkpoint","height","negative_prompt","prompt","seed","steps","width","batch_size"],
           "defaults": {"width": 1024, "height": 1024, "steps": 25, "cfg": 7, "seed": 42, ...}}],
 "errors": {"broken-file": "why it failed to load"}}
```

`GET /v1/comfy/checkpoints` lists checkpoint files ComfyUI knows about.

### Job API (recommended)

`POST /v1/images/jobs` → `202` with a job. Body (all optional except `prompt`):

```json
{"prompt": "a lighthouse at dawn", "workflow": "sdxl-basic", "negative_prompt": "blurry",
 "size": "1024x1024", "width": 1024, "height": 1024, "steps": 25, "cfg": 6.5,
 "seed": 1234, "n": 2, "checkpoint": "sd_xl_base_1.0.safetensors"}
```

- `workflow` (alias `model`) defaults to the first workflow alphabetically.
- `seed` omitted or `-1` = random. The seed actually used is returned per image.
- `n` (1–8) uses the workflow's `batch_size` if it has one, otherwise runs the workflow `n` times with seeds `seed, seed+1, …`.
- Sizes are rounded to multiples of 8 and clamped to 64–4096.

Job object:

```json
{"id": "img_3f2c...", "object": "image.job", "status": "queued|running|succeeded|failed|cancelled",
 "workflow": "sdxl-basic", "params": {...}, "created_at": 1730540000, "started_at": ..., "finished_at": ...,
 "progress": "generating", "queue_position": 0, "error": null,
 "images": [{"url": "https://.../v1/files/img_3f2c.../0_0.png?exp=...&sig=...", "path": "/v1/files/...", "seed": 1234}]}
```

| Method | Path | |
|---|---|---|
| GET | `/v1/images/jobs/{id}?wait=30` | Get a job. `wait` (≤ 60 s) long-polls until it finishes. |
| GET | `/v1/images/jobs?limit=50&before=<created_at>&status=succeeded` | History, newest first. Use `next_before` to page. |
| POST | `/v1/images/jobs/{id}/cancel` | Removes it from the queue, or interrupts it in ComfyUI. |
| DELETE | `/v1/images/jobs/{id}` | Deletes the job and its image files. |

Image `url`s are signed and valid for about `file_url_ttl_days` (default 2), so they work directly
in `<img src>`. Lists re-sign on every request. The same files are also served to
`Authorization`-authenticated requests at the bare path.

### OpenAI-compatible (blocking)

`POST /v1/images/generations` with OpenAI's body (`prompt`, `model`=workflow, `n`, `size`,
`response_format: "url" | "b64_json"`) waits for the job and returns
`{"created": ..., "data": [{"url": ...}], "job_id": ...}`. Fine on the LAN or Tailscale. Behind
Cloudflare, prefer the job API (100 s idle limit). If the client disconnects, the job keeps running
and appears in the job history.

## Health

- `GET /health`: no auth, for uptime checks.
- `GET /v1/status`: upstream reachability, ComfyUI GPU/VRAM, queue counts, workflow load errors.
