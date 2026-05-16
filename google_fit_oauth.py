from __future__ import annotations

import json
import os
import secrets
import threading
import time
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

import requests
from dotenv import load_dotenv, set_key


load_dotenv()

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
DEFAULT_REDIRECT_URI = "http://localhost:8080/callback"
SCOPES = [
    "https://www.googleapis.com/auth/fitness.activity.read",
    "https://www.googleapis.com/auth/fitness.location.read",
    "https://www.googleapis.com/auth/fitness.body.read",
]


class OAuthCallbackHandler(BaseHTTPRequestHandler):
    server: "OAuthServer"

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)

        if parsed.path != self.server.callback_path:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Unknown callback path.")
            return

        state = query.get("state", [""])[0]
        if state != self.server.expected_state:
            self.server.error = "OAuth state mismatch."
            self._send_page("OAuth state mismatch. You can close this tab.")
            return

        error = query.get("error", [""])[0]
        if error:
            self.server.error = error
            self._send_page(f"Google returned an OAuth error: {error}. You can close this tab.")
            return

        code = query.get("code", [""])[0]
        if not code:
            self.server.error = "No authorization code received."
            self._send_page("No authorization code received. You can close this tab.")
            return

        self.server.code = code
        self._send_page("Google Fit authorization finished. You can close this tab.")

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _send_page(self, message: str) -> None:
        body = f"<html><body><h1>{message}</h1></body></html>".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class OAuthServer(HTTPServer):
    code: str | None = None
    error: str | None = None

    def __init__(self, server_address: tuple[str, int], handler: type[OAuthCallbackHandler], expected_state: str, callback_path: str):
        super().__init__(server_address, handler)
        self.expected_state = expected_state
        self.callback_path = callback_path


def build_auth_url(client_id: str, redirect_uri: str, state: str) -> str:
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{AUTH_URL}?{urllib.parse.urlencode(params)}"


def exchange_code(client_id: str, client_secret: str, redirect_uri: str, code: str) -> dict[str, Any]:
    response = requests.post(
        TOKEN_URL,
        data={
            "client_id": client_id,
            "client_secret": client_secret,
            "redirect_uri": redirect_uri,
            "code": code,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    response.raise_for_status()
    return response.json()


def load_client_config() -> tuple[str | None, str | None, str]:
    client_id = os.getenv("GOOGLE_FIT_CLIENT_ID")
    client_secret = os.getenv("GOOGLE_FIT_CLIENT_SECRET")
    redirect_uri = os.getenv("GOOGLE_FIT_REDIRECT_URI")

    if client_id and client_secret:
        return client_id, client_secret, redirect_uri or DEFAULT_REDIRECT_URI

    client_file = os.getenv("GOOGLE_FIT_CLIENT_SECRET_FILE")
    candidates = [Path(client_file)] if client_file else sorted(Path(".").glob("client_secret_*.json"))
    if not candidates:
        return client_id, client_secret, redirect_uri or DEFAULT_REDIRECT_URI

    payload = json.loads(candidates[0].read_text(encoding="utf-8"))
    config = payload.get("installed") or payload.get("web") or {}
    redirect_uris = config.get("redirect_uris") or []
    selected_redirect_uri = redirect_uri or first_local_redirect_uri(redirect_uris) or DEFAULT_REDIRECT_URI
    return config.get("client_id"), config.get("client_secret"), selected_redirect_uri


def first_local_redirect_uri(redirect_uris: list[str]) -> str | None:
    for redirect_uri in redirect_uris:
        parsed = urllib.parse.urlparse(redirect_uri)
        if parsed.hostname in {"localhost", "127.0.0.1"}:
            if not parsed.port and parsed.path in {"", "/"}:
                return DEFAULT_REDIRECT_URI
            return redirect_uri
    return None


def save_tokens(payload: dict[str, Any]) -> None:
    expires_at = str(int(time.time()) + int(payload.get("expires_in", 3600)) - 60)

    set_key(".env", "GOOGLE_FIT_ACCESS_TOKEN", payload["access_token"])
    if payload.get("refresh_token"):
        set_key(".env", "GOOGLE_FIT_REFRESH_TOKEN", payload["refresh_token"])
    set_key(".env", "GOOGLE_FIT_TOKEN_EXPIRES_AT", expires_at)

    with open("google_fit_tokens.json", "w", encoding="utf-8") as token_file:
        json.dump(
            {
                "access_token_set": bool(payload.get("access_token")),
                "refresh_token_set": bool(payload.get("refresh_token")),
                "expires_at": expires_at,
                "scope": payload.get("scope"),
                "token_type": payload.get("token_type"),
            },
            token_file,
            indent=2,
        )


def main() -> None:
    client_id, client_secret, redirect_uri = load_client_config()

    if not client_id or not client_secret:
        raise SystemExit(
            "Bitte GOOGLE_FIT_CLIENT_ID und GOOGLE_FIT_CLIENT_SECRET in .env setzen. "
            "Erstelle dafuer in Google Cloud einen OAuth Client mit Redirect URI "
            f"{redirect_uri}."
        )

    parsed_redirect = urllib.parse.urlparse(redirect_uri)
    if parsed_redirect.hostname not in {"localhost", "127.0.0.1"}:
        raise SystemExit("Dieser Helper unterstuetzt nur localhost Redirect URIs.")

    port = parsed_redirect.port or 8080
    callback_path = parsed_redirect.path or "/"
    state = secrets.token_urlsafe(24)
    server = OAuthServer(("localhost", port), OAuthCallbackHandler, state, callback_path)

    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    auth_url = build_auth_url(client_id, redirect_uri, state)
    print("Oeffne diese URL und erlaube den Google-Fit-Zugriff:")
    print(auth_url)
    webbrowser.open(auth_url)

    try:
        while server.code is None and server.error is None:
            time.sleep(0.2)
    finally:
        server.shutdown()

    if server.error:
        raise SystemExit(server.error)

    assert server.code is not None
    payload = exchange_code(client_id, client_secret, redirect_uri, server.code)
    save_tokens(payload)
    print("Google-Fit-Tokens wurden in .env gespeichert.")


if __name__ == "__main__":
    main()
