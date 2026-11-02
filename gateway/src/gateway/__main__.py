"""Command-line entry point: `python -m gateway` or `local-ai-gateway`."""

from __future__ import annotations

import argparse
import logging
import secrets
import sys

import uvicorn

from .config import load_settings, prepare_runtime


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="local-ai-gateway", description="OpenAI-compatible gateway for LM Studio + ComfyUI")
    ap.add_argument("--config", help="path to config.toml (default: ./config.toml or $GATEWAY_CONFIG)")
    ap.add_argument("--host", help="bind address (default 127.0.0.1)")
    ap.add_argument("--port", type=int, help="port (default 8787)")
    ap.add_argument("--show-key", action="store_true", help="print the API key in use and exit")
    ap.add_argument("--new-key", action="store_true", help="print a fresh random key to paste into config.toml and exit")
    ap.add_argument("--log-level", default="info")
    args = ap.parse_args(argv)

    if args.new_key:
        print("sk-local-" + secrets.token_urlsafe(32))
        return

    settings = load_settings(args.config)
    if args.host:
        settings.host = args.host
    if args.port:
        settings.port = args.port
    prepare_runtime(settings)

    if args.show_key:
        for k in settings.api_keys:
            print(k)
        return

    logging.basicConfig(level=args.log_level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    log = logging.getLogger("gateway")
    if settings.generated_key_path:
        log.info("No api_keys configured; using the generated key stored in %s", settings.generated_key_path)
        log.info("Show it with: local-ai-gateway --show-key")
    if settings.host not in ("127.0.0.1", "localhost", "::1"):
        log.warning("Listening on %s: anything that can reach this address can try your API key. "
                    "Prefer 127.0.0.1 + Tailscale Serve / Cloudflare Tunnel (see docs/remote-access.md).", settings.host)
    log.info("Chat upstreams: %s", ", ".join(f"{u.name}={u.base_url}" for u in settings.chat_upstreams))
    log.info("ComfyUI: %s (%s)", settings.comfyui.base_url, "enabled" if settings.comfyui.enabled else "disabled")
    log.info("Workflows folder: %s", settings.comfyui.workflows_dir)

    from .app import create_app  # imported late so --help/--new-key stay fast

    app = create_app(settings)
    try:
        uvicorn.run(app, host=settings.host, port=settings.port, log_level=args.log_level,
                    proxy_headers=True, forwarded_allow_ips="127.0.0.1")
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
