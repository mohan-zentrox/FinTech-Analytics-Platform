"""
JWT verification for the analytics endpoints.

docs/ARCHITECTURE.md previously recorded that this service had no auth of its own
and relied on same-VPC network trust. That is fine behind an internal ingress and
wrong the moment the service is reachable from the internet - which is exactly
what happens on the free hosting this repo now documents. So /analytics/** now
validates the *same* HS256 tokens core-api issues, using the shared JWT_SECRET.

There is no user lookup here: this service has no write access and no user table
of its own, so the token's signature, expiry and role claim are all it needs.
"""
from typing import Annotated, Optional

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import MIN_JWT_SECRET_BYTES, Settings, get_settings

# auto_error=False so a missing header reaches our own handler and can be allowed
# through when analytics_require_auth is false.
_bearer = HTTPBearer(auto_error=False)

# Roles permitted to read analytics. Mirrors core-api's
# @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')") on the read endpoints.
READ_ROLES = frozenset({"ADMIN", "ANALYST", "VIEWER"})


class Principal:
    """The authenticated caller, or the anonymous stand-in when auth is disabled."""

    def __init__(self, username: Optional[str], role: Optional[str]):
        self.username = username
        self.role = role

    @property
    def is_anonymous(self) -> bool:
        return self.username is None

    def __repr__(self) -> str:
        return f"Principal(username={self.username!r}, role={self.role!r})"


ANONYMOUS = Principal(None, None)


def _unauthorized(detail: str) -> HTTPException:
    # WWW-Authenticate is part of a correct 401 and is what tells a client to
    # re-authenticate rather than simply retry.
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
        headers={"WWW-Authenticate": "Bearer"},
    )


def decode_token(token: str, settings: Settings) -> Principal:
    """
    Verifies signature and expiry, then extracts the subject and role claim.

    Raises HTTPException(401) on any verification failure, and never echoes the
    library's message back to the caller - that would distinguish "expired" from
    "bad signature" for an attacker probing tokens.
    """
    secret = settings.jwt_secret
    if not secret or len(secret.encode("utf-8")) < MIN_JWT_SECRET_BYTES:
        # A misconfigured secret must fail closed, not verify everything.
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="JWT_SECRET is missing or shorter than 32 bytes; analytics cannot verify tokens",
        )

    try:
        claims = jwt.decode(
            token,
            secret,
            algorithms=["HS256"],  # pinned: never let the token pick its own algorithm
            options={"require": ["exp", "sub"]},
        )
    except jwt.PyJWTError:
        raise _unauthorized("Invalid or expired token")

    return Principal(claims.get("sub"), claims.get("role"))


def require_reader(
    credentials: Annotated[Optional[HTTPAuthorizationCredentials], Depends(_bearer)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Principal:
    """
    FastAPI dependency guarding every analytics route.

    Returns the Principal so handlers (and tests) can see who called, and rejects
    a token whose role is not permitted to read analytics.
    """
    if not settings.analytics_require_auth:
        return ANONYMOUS

    if credentials is None or not credentials.credentials:
        raise _unauthorized("Missing bearer token")

    principal = decode_token(credentials.credentials, settings)

    if principal.role not in READ_ROLES:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Role {principal.role!r} is not permitted to read analytics",
        )
    return principal
