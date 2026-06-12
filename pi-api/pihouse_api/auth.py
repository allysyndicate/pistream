from __future__ import annotations

import secrets
from pathlib import Path

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import AppConfig

bearer = HTTPBearer(auto_error=False)


def get_config(request: Request) -> AppConfig:
    return request.app.state.config


def load_token(config: AppConfig) -> str:
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
