# Android app

## Install

Download the latest APK from the repo's **Releases** page (`HearthLight-<version>.apk`), open it
on the phone, and allow "install unknown apps" for your browser/file manager when Android asks.
Installing a newer APK over an older one keeps your chats and settings.

Every push also builds a debug APK in **Actions → CI → latest run → Artifacts → `local-ai-chat-debug-apk`**.

Or build it yourself: open the `android/` folder in Android Studio and press Run, or
`cd android && ./gradlew :app:assembleDebug` (needs the Android SDK; output in `app/build/outputs/apk/debug/`).

Requires Android 10 or newer.

## First run

The app opens on **Settings**:
- **Gateway URL:** your Tailscale Serve URL (`https://my-pc.tail1234.ts.net`) or Cloudflare hostname. `/v1` at the end is optional.
- **API key:** from `local-ai-gateway --show-key` on the PC. It is stored encrypted with the Android Keystore.
- Tap **Test connection**. It shows LM Studio's and ComfyUI's status and your GPU.

## Features

- **Chat:** streaming replies, Markdown (code blocks with copy, tables, lists), stop button, regenerate, copy.
  Reasoning models (`reasoning_content` or `<think>` tags) show a collapsible "Thinking" section.
- **Model picker** in the title bar lists the models LM Studio offers. The choice is remembered per chat.
- **History:** chats are saved on the phone (drawer → Recent). Delete with the bin icon.
- **Attachments:** tap **+** for Camera, Photos, Files, Record audio or Audio file, or the mic to record a voice note.
  Photos go to vision models, documents (PDF, Word, PowerPoint, text, code) become text, voice notes are transcribed
  on your PC. See [attachments.md](attachments.md).
- **System prompt and temperature** in Settings.
- **Images:** pick a workflow, describe the image, choose Square/Portrait/Landscape, optionally steps/seed/count.
  Jobs run on the PC, so you can close the app and come back. Tap an image to save it to *Pictures/LocalAI*, share it, reuse its prompt or delete it.

## Notes

- The app allows plain `http://` so a raw Tailscale IP works (Tailscale encrypts it). It warns you if you enter an `http://` address that isn't private.
- Voice notes need speech-to-text on the PC: see [attachments.md](attachments.md#setting-up-speech-to-text-whisper).
- Not yet: img2img, spoken replies (text-to-speech).
