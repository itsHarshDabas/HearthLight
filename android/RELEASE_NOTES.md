## What's new: attachments in chat

Tap **+** next to the message box:

- **Camera**: take a photo and ask about it
- **Photos**: attach up to 10 images from your gallery
- **Files**: PDF, Word, PowerPoint, text and code files are turned into text for the model
- **Record audio** (or the mic icon): voice notes are transcribed on your PC with Whisper
- **Audio file**: transcribe an existing recording

Photos need a **vision model** loaded in LM Studio (Qwen2.5-VL, Gemma 3, LLaVA...).
Voice notes need speech-to-text on the PC: in the gateway folder run `.venv\Scripts\pip install -e ".[audio]"` and restart it.
Full guide: `docs/attachments.md`.

### Update steps

1. **PC:** update the gateway (`git pull`, or unzip `HearthLight-gateway-*.zip` over your gateway folder, keeping `config.toml` and `data/`), then run `.venv\Scripts\pip install -e ".[audio]"` and restart it.
2. **Phone:** install `HearthLight-*.apk`. If Android says the app "conflicts with an existing package", uninstall the old version first (earlier builds were signed with a temporary key).
