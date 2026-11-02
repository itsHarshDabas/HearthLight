"""Configuration loading.

Order of precedence (highest first):
  1. Environment variables (GATEWAY_*)
  2. config.toml (path from GATEWAY_CONFIG, default ./config.toml)
  3. Built-in defaults
"""

from __future__ import annotations

import os
import secrets
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ChatUpstream:
    name: str
    base_url: str  # must include the /v1 suffix, e.g. http://127.0.0.1:1234/v1
    api_key: str = ""
    timeout: float = 600.0

    def __post_init__(self) -> None:
        self.base_url = self.base_url.rstrip("/")


@dataclass
class ComfyConfig:
    enabled: bool = True
    base_url: str = "http://127.0.0.1:8188"
    workflows_dir: str = "workflows"
    max_concurrent_jobs: int = 1
    job_timeout: float = 1800.0
    poll_interval: float = 1.0

    def __post_init__(self) -> None:
        self.base_url = self.base_url.rstrip("/")


@dataclass
class Settings:
    host: str = "127.0.0.1"
    port: int = 8787
    public_base_url: str = ""
    cors_origins: list[str] = field(default_factory=list)
    enable_docs: bool = True
    api_keys: list[str] = field(default_factory=list)
    default_model: str = ""
    data_dir: Path = Path("data")
    chat_upstreams: list[ChatUpstream] = field(default_factory=list)
    comfyui: ComfyConfig = field(default_factory=ComfyConfig)
    file_url_ttl_days: int = 2
    # Resolved at startup
    signing_secret: bytes = b""
    generated_key_path: Path | None = None

    @property
    def images_dir(self) -> Path:
        return self.data_dir / "images"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "gateway.db"


def _split_env_list(value: str) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()]


def load_settings(config_path: str | os.PathLike | None = None, *, env: dict | None = None) -> Settings:
    env = dict(os.environ if env is None else env)
    path = Path(config_path or env.get("GATEWAY_CONFIG", "config.toml"))
    raw: dict = {}
    if path.is_file():
        with path.open("rb") as fh:
            raw = tomllib.load(fh)
    base_dir = path.parent.resolve() if path.is_file() else Path.cwd()

    server = raw.get("server", {})
    auth = raw.get("auth", {})
    chat = raw.get("chat", {})
    comfy = raw.get("comfyui", {})
    storage = raw.get("storage", {})

    s = Settings(
        host=server.get("host", "127.0.0.1"),
        port=int(server.get("port", 8787)),
        public_base_url=str(server.get("public_base_url", "")).rstrip("/"),
        cors_origins=list(server.get("cors_origins", [])),
        enable_docs=bool(server.get("enable_docs", True)),
        api_keys=[k for k in auth.get("api_keys", []) if k],
        default_model=chat.get("default_model", ""),
        data_dir=Path(storage.get("data_dir", "data")),
        file_url_ttl_days=int(storage.get("file_url_ttl_days", 2)),
    )

    ups = chat.get("upstreams")
    if ups is None:
        ups = [{"name": "lmstudio", "base_url": "http://127.0.0.1:1234/v1"}]
    s.chat_upstreams = [ChatUpstream(**u) for u in ups]

    s.comfyui = ComfyConfig(**comfy) if comfy else ComfyConfig()

    # --- environment overrides -------------------------------------------
    if "GATEWAY_HOST" in env:
        s.host = env["GATEWAY_HOST"]
    if "GATEWAY_PORT" in env:
        s.port = int(env["GATEWAY_PORT"])
    if "GATEWAY_PUBLIC_BASE_URL" in env:
        s.public_base_url = env["GATEWAY_PUBLIC_BASE_URL"].rstrip("/")
    if "GATEWAY_API_KEYS" in env:
        s.api_keys = _split_env_list(env["GATEWAY_API_KEYS"])
    if "GATEWAY_CORS_ORIGINS" in env:
        s.cors_origins = _split_env_list(env["GATEWAY_CORS_ORIGINS"])
    if "GATEWAY_DATA_DIR" in env:
        s.data_dir = Path(env["GATEWAY_DATA_DIR"])
    if "GATEWAY_LMSTUDIO_URL" in env and s.chat_upstreams:
        s.chat_upstreams[0].base_url = env["GATEWAY_LMSTUDIO_URL"].rstrip("/")
    if "GATEWAY_COMFYUI_URL" in env:
        s.comfyui.base_url = env["GATEWAY_COMFYUI_URL"].rstrip("/")
    if "GATEWAY_DEFAULT_MODEL" in env:
        s.default_model = env["GATEWAY_DEFAULT_MODEL"]

    # Relative paths are relative to the config file's folder.
    if not s.data_dir.is_absolute():
        s.data_dir = (base_dir / s.data_dir).resolve()
    wf = Path(s.comfyui.workflows_dir)
    if not wf.is_absolute():
        s.comfyui.workflows_dir = str((base_dir / wf).resolve())

    return s


def prepare_runtime(s: Settings) -> None:
    """Create data folders, the URL-signing secret and, if needed, an API key."""
    s.data_dir.mkdir(parents=True, exist_ok=True)
    s.images_dir.mkdir(parents=True, exist_ok=True)

    secret_path = s.data_dir / "signing.key"
    if not secret_path.exists():
        secret_path.write_bytes(secrets.token_bytes(32))
        _restrict(secret_path)
    s.signing_secret = secret_path.read_bytes()

    if not s.api_keys:
        key_path = s.data_dir / "api_key.txt"
        if not key_path.exists():
            key_path.write_text("sk-local-" + secrets.token_urlsafe(32) + "\n", encoding="utf-8")
            _restrict(key_path)
        s.api_keys = [key_path.read_text(encoding="utf-8").strip()]
        s.generated_key_path = key_path


def _restrict(path: Path) -> None:
    try:
        os.chmod(path, 0o600)
    except OSError:  # Windows ignores most modes; the file lives in the user's profile anyway
        pass
