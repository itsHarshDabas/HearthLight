"""API-key auth and signed file URLs."""

from __future__ import annotations

import hashlib
import hmac
import time

from fastapi import HTTPException, Request

from .config import Settings


def openai_error(status: int, message: str, type_: str = "invalid_request_error", code: str | None = None) -> HTTPException:
    return HTTPException(status_code=status, detail={"error": {"message": message, "type": type_, "code": code}})


def extract_key(request: Request) -> str | None:
    auth = request.headers.get("authorization", "")
    if auth[:7].lower() == "bearer ":
        return auth[7:].strip()
    return request.headers.get("x-api-key")


def key_is_valid(settings: Settings, key: str | None) -> bool:
    if not key:
        return False
    supplied = key.encode()
    ok = False
    # Compare against every key (no early exit) so timing doesn't reveal which matched.
    for candidate in settings.api_keys:
        ok |= hmac.compare_digest(supplied, candidate.encode())
    return ok


def require_key(request: Request) -> None:
    settings: Settings = request.app.state.settings
    if not key_is_valid(settings, extract_key(request)):
        raise openai_error(401, "Invalid or missing API key. Send 'Authorization: Bearer <key>'.", "authentication_error", "invalid_api_key")


# --- signed file URLs ------------------------------------------------------

def _sig(secret: bytes, path: str, exp: int) -> str:
    return hmac.new(secret, f"{path}\n{exp}".encode(), hashlib.sha256).hexdigest()[:32]


def sign_path(settings: Settings, path: str, now: float | None = None) -> str:
    """Return `path?exp=..&sig=..`.

    Expiry is rounded up to a day boundary so the same image gets the same URL
    for a whole day, which keeps client-side image caches effective.
    """
    now = time.time() if now is None else now
    day = 86400
    exp = (int(now) // day + max(1, settings.file_url_ttl_days)) * day
    return f"{path}?exp={exp}&sig={_sig(settings.signing_secret, path, exp)}"


def verify_signed(settings: Settings, path: str, exp: str | None, sig: str | None) -> bool:
    if not exp or not sig:
        return False
    try:
        exp_i = int(exp)
    except ValueError:
        return False
    if exp_i < time.time():
        return False
    return hmac.compare_digest(_sig(settings.signing_secret, path, exp_i), sig)
