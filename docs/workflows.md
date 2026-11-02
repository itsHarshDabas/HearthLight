# Using your own ComfyUI workflows

1. Build and test the workflow in ComfyUI as usual.
2. Export it in **API format**: *Workflow* menu → **Export (API)**. (Older UI: enable dev mode in settings, then *Save (API Format)*.) A normal *Save* produces UI format, which the gateway rejects with a clear message.
3. Save the file as `gateway/workflows/<name>.json`. `<name>` becomes the workflow id. Files are picked up automatically.
4. Check `GET /v1/workflows` (or the app's workflow picker). Problems are listed under `errors` and in `/v1/status`.

The workflow must contain a **Save Image** node; its outputs are what the gateway collects.
(Preview Image outputs are used only if there's no Save Image.)

## How parameters get injected

For each request parameter the gateway needs to know which node input to overwrite. Three ways,
the first match wins:

**1. Auto-detection (works for most standard graphs, nothing to do)**

- `prompt` / `negative_prompt`: text encoders found by following the sampler's `positive` / `negative`
  links (also through Flux `BasicGuider` → `FluxGuidance` → `CLIPTextEncode`, `ConditioningCombine`, …).
  SDXL `text_g`/`text_l` are both filled.
- `seed`: every `seed` / `noise_seed` input.
- `steps`: on samplers and schedulers. `cfg`: on samplers and guiders.
- `width`, `height`, `batch_size`: on `Empty…Latent…` nodes (and `ModelSamplingFlux` width/height).
- `checkpoint`: `ckpt_name` on checkpoint loaders.

**2. Placeholders.** Put `{{prompt}}` in any text field, e.g. `"masterpiece, {{prompt}}, film grain"`, or
set a widget to `"{{seed}}"`, `"{{steps}}"`, … Known names: `prompt`, `negative_prompt`, `width`, `height`,
`batch_size`, `seed`, `steps`, `cfg`, `checkpoint`. Placeholders replace auto-detection for that parameter.
Give numeric placeholders a default in a meta file. Otherwise width/height default to 1024, steps 20, cfg 7, batch 1.

**3. A meta file** `<name>.meta.json` next to the workflow, for full control plus a nice title:

```json
{
  "title": "Portrait (Juggernaut XL)",
  "description": "Tuned for faces. 30 steps.",
  "params": {
    "prompt":   [{"node": "6", "input": "text"}],
    "negative_prompt": [{"node": "7", "input": "text"}],
    "seed":     [{"node": "3", "input": "seed"}, {"node": "12", "input": "noise_seed"}]
  },
  "defaults": {"width": 896, "height": 1152, "steps": 30, "negative_prompt": "lowres, bad hands"}
}
```

Node ids are the keys in the API-format JSON. Defaults otherwise come from the values saved in the
workflow. The example prompt text in the file is never used as a default.

## Tips

- Keep one workflow per "style" and give it a meta `title`. That's what the phone shows.
- Custom nodes are fine as long as they're installed in ComfyUI. The gateway never needs to know about them.
- If LM Studio and ComfyUI share a GPU, big chat models plus SDXL/Flux can run out of VRAM. Unload the LLM, or use LM Studio's idle auto-unload (TTL).
