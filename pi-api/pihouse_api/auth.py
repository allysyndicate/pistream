from __future__ import annotations

import os
import secrets
from pathlib import Path

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import AppConfig

bearer = HTTPBearer(auto_error=False)
TOKEN_ENV_KEY = "PISTREAM_API_TOKEN"


def get_config(request: Request) -> AppConfig:
    return request.app.state.config


def _load_env_file_token(config: AppConfig) -> str | None:
    env_path = Path(config.tokenFile).parent / ".env"
    if not env_path.exists():
        return None
    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == TOKEN_ENV_KEY:
            return value.strip().strip('"').strip("'")
    return None


def load_token(config: AppConfig) -> str:
    token = os.environ.get(TOKEN_ENV_KEY, "").strip()
    if not token:
        token = (_load_env_file_token(config) or "").strip()
    if token:
        return token

    token = Path(config.tokenFile).read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError("token file is empty")
    return token


def unauthorized() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={
            "code": "unauthorized",
            "message": "Authorization is required.",
            "details": {"reason": "missing_or_invalid_bearer_token"},
        },
        headers={"WWW-Authenticate": "Bearer"},
    )


def require_bearer(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    config: AppConfig = Depends(get_config),
) -> None:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise unauthorized()
    expected = load_token(config)
    if not secrets.compare_digest(credentials.credentials, expected):
        raise unauthorized()


def maybe_health_auth(request: Request, config: AppConfig = Depends(get_config)) -> None:
    if not config.healthRequiresAuth:
        return
    header = request.headers.get("authorization", "")
    prefix = "Bearer "
    if not header.startswith(prefix):
        raise unauthorized()
    expected = load_token(config)
    if not secrets.compare_digest(header[len(prefix) :], expected):
        raise unauthorized()
