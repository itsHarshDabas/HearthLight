import shutil
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).parent))

from fakes import ServerThread, fake_comfyui, fake_lmstudio  # noqa: E402
from gateway.app import create_app  # noqa: E402
from gateway.config import load_settings  # noqa: E402

REPO_WORKFLOWS = Path(__file__).parents[1] / "workflows"
KEY = "sk-test-key-123"


@pytest.fixture(scope="session")
def lmstudio():
    with ServerThread(fake_lmstudio()) as s:
        yield s


@pytest.fixture(scope="session")
def comfy():
    with ServerThread(fake_comfyui()) as s:
        yield s


@pytest.fixture()
def workflows_dir(tmp_path):
    d = tmp_path / "workflows"
    shutil.copytree(REPO_WORKFLOWS, d)
    return d


def make_settings(tmp_path, lm_url, comfy_url, workflows_dir, **extra_env):
    env = {
        "GATEWAY_API_KEYS": KEY,
        "GATEWAY_DATA_DIR": str(tmp_path / "data"),
        "GATEWAY_LMSTUDIO_URL": lm_url + "/v1",
        "GATEWAY_COMFYUI_URL": comfy_url,
        **extra_env,
    }
    s = load_settings(tmp_path / "nonexistent.toml", env=env)
    s.comfyui.workflows_dir = str(workflows_dir)
    s.comfyui.poll_interval = 0.05
    return s


@pytest.fixture()
def gateway(tmp_path, lmstudio, comfy, workflows_dir):
    settings = make_settings(tmp_path, lmstudio.url, comfy.url, workflows_dir)
    with ServerThread(create_app(settings)) as gw:
        gw.settings = settings
        yield gw


@pytest.fixture()
def client(gateway):
    with httpx.Client(base_url=gateway.url, headers={"Authorization": f"Bearer {KEY}"}, timeout=30) as c:
        yield c
