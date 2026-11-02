"""Run fake LM Studio (port 1234) and fake ComfyUI (port 8188) for trying the gateway or
Android app without a GPU. Usage:  python dev/run_fakes.py"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))

from fakes import ServerThread, fake_comfyui, fake_lmstudio  # noqa: E402

if __name__ == "__main__":
    with ServerThread(fake_lmstudio(token_delay=0.05), port=1234) as lm, ServerThread(fake_comfyui(3.0), port=8188) as c:
        print(f"fake LM Studio: {lm.url}/v1   fake ComfyUI: {c.url}   (Ctrl+C to stop)")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
