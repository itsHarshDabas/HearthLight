# Attachments: photos, files and voice notes

Tap **+** next to the message box in the app (or the mic icon to record right away).

| Option | What happens | Needs on the PC |
|---|---|---|
| **Camera** | Takes a photo with your camera app and attaches it. | A **vision model** loaded in LM Studio. |
| **Photos** | Pick up to 10 images from the system photo picker. | A vision model. |
| **Files** | Any file. Images are sent as images, audio is transcribed, everything else becomes text. | Gateway 0.2+ for PDF / Word / PowerPoint. |
| **Record audio** / mic icon | Records a voice note, then turns it into text with Whisper. | Speech-to-text set up (below). |
| **Audio file** | Pick an existing recording (m4a, mp3, wav, ogg, opus, flac...). | Speech-to-text set up. |

Attachments show as chips above the message box while they're being prepared. Tap the **×** to
remove one. You can send attachments with or without typed text.

## How each kind reaches the model

- **Images** are resized on the phone (longest side 1536 px, JPEG) and sent as OpenAI-style
  `image_url` content parts. The gateway passes them straight through to LM Studio.
  If the current model can't see images, the app says so: load a vision model such as
  **Qwen2.5-VL**, **Gemma 3**, **LLaVA** or **MiniCPM-V** in LM Studio and pick it in the title bar.
- **Text and code files** (`.txt`, `.md`, `.py`, `.kt`, `.json`, `.csv`...) are read on the phone.
- **PDF, DOCX and PPTX** are sent to the gateway (`POST /v1/attachments/extract`), which returns their text.
  Scanned PDFs have no text layer; send photos of the pages to a vision model instead.
  Document text is added to your message inside `<file name="...">...</file>` blocks (cut at 200,000 characters).
- **Audio** is transcribed by the gateway (`POST /v1/audio/transcriptions`, OpenAI-compatible) and the
  transcript is sent as text. The recording stays on the phone, and you can replay it from the chat.

Everything stays on your phone and your PC. Attachments are stored with the chat on the phone and deleted with it.

## Setting up speech-to-text (Whisper)

LM Studio can't transcribe audio, so the gateway does it. Pick one:

### Option A: local Whisper inside the gateway (recommended)

```powershell
cd gateway
.\.venv\Scripts\pip install -e ".[audio]"
```

(or answer **Y** when `scripts\setup.ps1` asks). Restart the gateway. The first voice note downloads
the model (about 150 MB for `base`), so it takes a little longer.

Tune it in `config.toml`:

```toml
[audio]
backend = "auto"        # picks local Whisper when it's installed
model = "base"          # tiny / base / small / medium / large-v3 / turbo
device = "auto"         # uses your NVIDIA GPU if CUDA 12 + cuDNN work, else the CPU
compute_type = "default"
language = ""           # "" = auto-detect, or "en", "hi", ...
```

`small` or `turbo` are noticeably better than `base` for Hindi or mixed-language speech and still fast on a GPU.
If the GPU libraries are missing, the gateway falls back to the CPU by itself (check the log).

### Option B: an existing Whisper server

If you already run a whisper.cpp server, [speaches](https://github.com/speaches-ai/speaches) or similar
with an OpenAI-compatible `/v1/audio/transcriptions` endpoint:

```toml
[audio]
backend = "openai"
upstream_url = "http://127.0.0.1:8080/v1"
upstream_model = "whisper-1"
```

### Check it

`GET /v1/status` now has an `audio` section, e.g. `{"enabled": true, "backend": "faster-whisper", "model": "base"}`.
In the app, **Settings → Test connection** shows whether voice notes will work.
If it isn't set up, recording still works but the app tells you transcription isn't available and doesn't send the audio.
