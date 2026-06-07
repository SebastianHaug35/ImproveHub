from __future__ import annotations

import json
import os
import secrets
import time
import urllib.parse
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import pandas as pd
import requests
import streamlit as st


APP_PORT = int(os.getenv("GOOGLE_HEALTH_FRESH_PORT", "8510"))
APP_REDIRECT_URI = os.getenv("GOOGLE_HEALTH_FRESH_REDIRECT_URI", f"http://localhost:{APP_PORT}")
GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo"
GOOGLE_HEALTH_BASE = "https://health.googleapis.com/v4"
TOKENS_FILE = Path(".google_health_fresh_tokens.json")
STATE_KEY = "google_health_fresh_oauth_state"

# Scopes chosen to support common read-only exploration (including pairedDevices/settings).
SCOPES = [
    "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
    "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
    "https://www.googleapis.com/auth/googlehealth.location.readonly",
    "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    "https://www.googleapis.com/auth/googlehealth.profile.readonly",
    "https://www.googleapis.com/auth/googlehealth.settings.readonly",
]

DATA_TYPES = {
    "Steps": "steps",
    "Active Minutes": "active-minutes",
    "Distance": "distance",
    "Active Energy": "active-energy-burned",
    "Heart Rate": "heart-rate",
    "Weight": "weight",
    "Sleep": "sleep",
    "Exercise": "exercise",
}


def load_client_credentials() -> tuple[str | None, str | None]:
    client_file = os.getenv("GOOGLE_HEALTH_CLIENT_SECRET_FILE")
    candidates = [Path(client_file)] if client_file else sorted(Path(".").glob("client_secret_*.json"))
    if not candidates:
        return None, None

    payload = json.loads(candidates[0].read_text(encoding="utf-8"))
    config = payload.get("web") or payload.get("installed") or {}
    return config.get("client_id"), config.get("client_secret")


def load_tokens() -> dict[str, Any]:
    if TOKENS_FILE.exists():
        return json.loads(TOKENS_FILE.read_text(encoding="utf-8"))
    return {}


def save_tokens(tokens: dict[str, Any]) -> None:
    TOKENS_FILE.write_text(json.dumps(tokens, indent=2), encoding="utf-8")


def clear_tokens() -> None:
    if TOKENS_FILE.exists():
        TOKENS_FILE.unlink()


def token_expired(expires_at: int | None) -> bool:
    if not expires_at:
        return False
    return int(time.time()) >= int(expires_at) - 60


def build_auth_url(client_id: str) -> str:
    state = st.session_state.get(STATE_KEY)
    if not state:
        state = secrets.token_urlsafe(24)
        st.session_state[STATE_KEY] = state

    params = {
        "client_id": client_id,
        "redirect_uri": APP_REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{GOOGLE_AUTH_URL}?{urllib.parse.urlencode(params)}"


def exchange_code(client_id: str, client_secret: str, code: str) -> dict[str, Any]:
    response = requests.post(
        GOOGLE_TOKEN_URL,
        data={
            "client_id": client_id,
            "client_secret": client_secret,
            "redirect_uri": APP_REDIRECT_URI,
            "code": code,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    payload["expires_at"] = int(time.time()) + int(payload.get("expires_in", 3600))
    return payload


def refresh_access_token(client_id: str, client_secret: str, refresh_token: str) -> dict[str, Any]:
    response = requests.post(
        GOOGLE_TOKEN_URL,
        data={
            "client_id": client_id,
            "client_secret": client_secret,
            "refresh_token": refresh_token,
            "grant_type": "refresh_token",
        },
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    payload["refresh_token"] = refresh_token
    payload["expires_at"] = int(time.time()) + int(payload.get("expires_in", 3600))
    return payload


def get_valid_access_token(client_id: str, client_secret: str) -> str:
    tokens = load_tokens()
    access_token = tokens.get("access_token")
    refresh_token = tokens.get("refresh_token")
    expires_at = tokens.get("expires_at")

    if access_token and not token_expired(expires_at):
        return access_token

    if refresh_token:
        updated = refresh_access_token(client_id, client_secret, refresh_token)
        save_tokens(updated)
        return updated["access_token"]

    raise RuntimeError("Kein gueltiger Token vorhanden. Bitte zuerst anmelden.")


def api_request(
    client_id: str,
    client_secret: str,
    method: str,
    path: str,
    *,
    params: dict[str, Any] | None = None,
    json_body: dict[str, Any] | None = None,
) -> dict[str, Any]:
    token = get_valid_access_token(client_id, client_secret)
    response = requests.request(
        method,
        f"{GOOGLE_HEALTH_BASE}{path}",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        params=params,
        json=json_body,
        timeout=60,
    )
    if response.status_code == 401:
        raise RuntimeError("401: Token ungueltig/abgelaufen. Bitte neu anmelden.")
    if response.status_code == 403:
        raise RuntimeError(f"403: Scope/Access fehlt. Antwort: {response.text}")
    response.raise_for_status()
    if response.text.strip():
        return response.json()
    return {}


def get_token_info() -> dict[str, Any] | None:
    tokens = load_tokens()
    access_token = tokens.get("access_token")
    if not access_token:
        return None

    response = requests.get(TOKEN_INFO_URL, params={"access_token": access_token}, timeout=30)
    if response.status_code != 200:
        return {"status": response.status_code, "error": response.text}
    return response.json()


def to_health_date(value: date) -> dict[str, int]:
    return {"year": value.year, "month": value.month, "day": value.day}


def run_daily_rollup(
    client_id: str,
    client_secret: str,
    data_type: str,
    start_date: date,
    end_date: date,
) -> dict[str, Any]:
    body = {
        "range": {
            "start": {"date": to_health_date(start_date)},
            "end": {"date": to_health_date(end_date + timedelta(days=1))},
        },
        "windowSizeDays": 1,
        "pageSize": 100,
        "dataSourceFamily": "users/me/dataSourceFamilies/all-sources",
    }
    return api_request(
        client_id,
        client_secret,
        "POST",
        f"/users/me/dataTypes/{data_type}/dataPoints:dailyRollUp",
        json_body=body,
    )


def handle_oauth_callback(client_id: str, client_secret: str) -> None:
    query_params = st.query_params
    code = query_params.get("code")
    state = query_params.get("state")
    error = query_params.get("error")

    if error:
        st.error(f"OAuth Fehler: {error}")
        return
    if not code:
        return

    expected = st.session_state.get(STATE_KEY)
    if expected and state != expected:
        st.error("OAuth state mismatch. Bitte erneut versuchen.")
        return

    payload = exchange_code(client_id, client_secret, code)
    save_tokens(payload)
    st.session_state.pop(STATE_KEY, None)
    st.query_params.clear()
    st.success("Login erfolgreich. Token gespeichert.")
    st.rerun()


def main() -> None:
    st.set_page_config(page_title="Google Health Fresh App", page_icon="H", layout="wide")
    st.title("Google Health Fresh App")
    st.caption("Neue, isolierte App ohne Altlasten. Nur hier anmelden, dann Daten direkt abrufen.")

    client_id, client_secret = load_client_credentials()
    if not client_id or not client_secret:
        st.error("Kein OAuth Client gefunden. Lege client_secret_*.json in den Projektordner.")
        st.stop()

    handle_oauth_callback(client_id, client_secret)

    with st.sidebar:
        st.header("Login")
        st.write(f"Redirect URI: {APP_REDIRECT_URI}")
        auth_url = build_auth_url(client_id)
        st.link_button("Mit Google anmelden", auth_url, use_container_width=True)
        if st.button("Abmelden (Token loeschen)", use_container_width=True):
            clear_tokens()
            st.success("Token geloescht.")
            st.rerun()

    token_info = get_token_info()
    with st.expander("OAuth Debug", expanded=False):
        st.json(
            {
                "tokens_file": str(TOKENS_FILE),
                "tokens_exist": TOKENS_FILE.exists(),
                "token_info": token_info,
            },
            expanded=False,
        )

    tabs = st.tabs(["Profile", "Paired Devices", "Rollup", "Raw Query"])

    with tabs[0]:
        st.subheader("GET /users/me/profile")
        if st.button("Profil laden"):
            try:
                payload = api_request(client_id, client_secret, "GET", "/users/me/profile")
                st.json(payload, expanded=False)
            except Exception as exc:
                st.error(str(exc))

    with tabs[1]:
        st.subheader("GET /users/me/pairedDevices")
        if st.button("Geraete laden"):
            try:
                payload = api_request(client_id, client_secret, "GET", "/users/me/pairedDevices")
                st.json(payload, expanded=False)
                rows = payload.get("pairedDevices", [])
                if rows:
                    frame = pd.json_normalize(rows)
                    st.dataframe(frame, use_container_width=True, hide_index=True)
            except Exception as exc:
                st.error(str(exc))

    with tabs[2]:
        st.subheader("POST /users/me/dataTypes/*/dataPoints:dailyRollUp")
        today = date.today()
        start = st.date_input("Start", value=today - timedelta(days=7), key="rollup_start")
        end = st.date_input("Ende", value=today, key="rollup_end")
        label = st.selectbox("Datentyp", options=list(DATA_TYPES.keys()))
        if st.button("Rollup laden"):
            if start > end:
                st.error("Start muss <= Ende sein.")
            else:
                try:
                    payload = run_daily_rollup(client_id, client_secret, DATA_TYPES[label], start, end)
                    st.json(payload, expanded=False)
                except Exception as exc:
                    st.error(str(exc))

    with tabs[3]:
        st.subheader("Freie API-Abfrage")
        method = st.selectbox("Methode", ["GET", "POST"])
        path = st.text_input("Path", value="/users/me/settings")
        body_text = st.text_area("JSON Body (optional)", value="{}", height=140)

        if st.button("Abfrage senden"):
            try:
                parsed_body = json.loads(body_text) if body_text.strip() else None
                payload = api_request(client_id, client_secret, method, path, json_body=parsed_body)
                st.json(payload, expanded=False)
            except Exception as exc:
                st.error(str(exc))


if __name__ == "__main__":
    main()
