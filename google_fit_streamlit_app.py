from __future__ import annotations

import os
import secrets
import time as time_module
import urllib.parse
from datetime import date, datetime, time as datetime_time, timedelta, timezone
from pathlib import Path
from typing import Any

import pandas as pd
import requests
import streamlit as st
from dotenv import load_dotenv, set_key


load_dotenv()

API_BASE_URL = os.getenv("GOOGLE_FIT_API_BASE_URL", "https://www.googleapis.com/fitness/v1").rstrip("/")
ACCESS_TOKEN = os.getenv("GOOGLE_FIT_ACCESS_TOKEN")
REFRESH_TOKEN = os.getenv("GOOGLE_FIT_REFRESH_TOKEN")
CLIENT_ID = os.getenv("GOOGLE_FIT_CLIENT_ID")
CLIENT_SECRET = os.getenv("GOOGLE_FIT_CLIENT_SECRET")
REDIRECT_URI = os.getenv("GOOGLE_FIT_REDIRECT_URI", "http://localhost:8503")
TOKEN_EXPIRES_AT = os.getenv("GOOGLE_FIT_TOKEN_EXPIRES_AT")
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
OAUTH_STATE_KEY = "GOOGLE_FIT_OAUTH_STATE"
SCOPES = [
    "https://www.googleapis.com/auth/fitness.activity.read",
    "https://www.googleapis.com/auth/fitness.location.read",
    "https://www.googleapis.com/auth/fitness.body.read",
]


AGGREGATES = {
    "Steps": {
        "data_type": "com.google.step_count.delta",
        "columns": ["steps"],
        "scope": "fitness.activity.read",
    },
    "Active Minutes": {
        "data_type": "com.google.active_minutes",
        "columns": ["active_minutes"],
        "scope": "fitness.activity.read",
    },
    "Heart Minutes": {
        "data_type": "com.google.heart_minutes",
        "columns": ["heart_minutes"],
        "scope": "fitness.activity.read",
    },
    "Calories": {
        "data_type": "com.google.calories.expended",
        "columns": ["calories"],
        "scope": "fitness.activity.read",
    },
    "Distance": {
        "data_type": "com.google.distance.delta",
        "columns": ["distance_m"],
        "scope": "fitness.location.read",
    },
    "Heart Rate": {
        "data_type": "com.google.heart_rate.bpm",
        "columns": ["heart_rate_avg", "heart_rate_max", "heart_rate_min"],
        "scope": "fitness.body.read",
    },
    "Weight": {
        "data_type": "com.google.weight",
        "columns": ["weight_avg", "weight_max", "weight_min"],
        "scope": "fitness.body.read",
    },
}


def load_client_config() -> None:
    global CLIENT_ID, CLIENT_SECRET, REDIRECT_URI

    if CLIENT_ID and CLIENT_SECRET:
        return

    client_file = os.getenv("GOOGLE_FIT_CLIENT_SECRET_FILE")
    candidates = [Path(client_file)] if client_file else sorted(Path(".").glob("client_secret_*.json"))
    if not candidates:
        return

    payload = json_load(candidates[0])
    config = payload.get("installed") or payload.get("web") or {}
    CLIENT_ID = config.get("client_id")
    CLIENT_SECRET = config.get("client_secret")


def json_load(path: Path) -> dict[str, Any]:
    import json

    return json.loads(path.read_text(encoding="utf-8"))


def build_auth_url() -> str:
    load_client_config()
    if not CLIENT_ID:
        return ""

    state = st.session_state.get("google_fit_oauth_state") or os.getenv(OAUTH_STATE_KEY)
    if not state:
        state = secrets.token_urlsafe(24)
        set_key(".env", OAUTH_STATE_KEY, state)
    st.session_state["google_fit_oauth_state"] = state
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{AUTH_URL}?{urllib.parse.urlencode(params)}"


def handle_oauth_callback() -> None:
    query_params = st.query_params
    code = query_params.get("code")
    state = query_params.get("state")
    error = query_params.get("error")

    if error:
        st.error(f"Google OAuth Fehler: {error}")
        return
    if not code:
        return
    expected_states = {
        value
        for value in [
            st.session_state.get("google_fit_oauth_state"),
            os.getenv(OAUTH_STATE_KEY),
        ]
        if value
    }
    if state not in expected_states:
        st.error("Google OAuth State passt nicht. Bitte Login erneut starten.")
        return

    load_client_config()
    if not CLIENT_ID or not CLIENT_SECRET:
        st.error("OAuth Client fehlt. Lege client_secret_*.json in den Projektordner.")
        return

    response = requests.post(
        TOKEN_URL,
        data={
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
            "redirect_uri": REDIRECT_URI,
            "code": code,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    response.raise_for_status()
    save_token_payload(response.json())
    st.session_state.pop("google_fit_oauth_state", None)
    set_key(".env", OAUTH_STATE_KEY, "")
    st.query_params.clear()
    st.cache_data.clear()
    st.success("Google-Fit-Login abgeschlossen.")
    st.rerun()


def save_token_payload(payload: dict[str, Any]) -> None:
    global ACCESS_TOKEN, REFRESH_TOKEN, TOKEN_EXPIRES_AT

    ACCESS_TOKEN = payload["access_token"]
    TOKEN_EXPIRES_AT = str(int(time_module.time()) + int(payload.get("expires_in", 3600)) - 60)
    set_key(".env", "GOOGLE_FIT_ACCESS_TOKEN", ACCESS_TOKEN)
    set_key(".env", "GOOGLE_FIT_TOKEN_EXPIRES_AT", TOKEN_EXPIRES_AT)

    if payload.get("refresh_token"):
        REFRESH_TOKEN = payload["refresh_token"]
        set_key(".env", "GOOGLE_FIT_REFRESH_TOKEN", REFRESH_TOKEN)


def request_google_fit(
    method: str,
    path: str,
    *,
    json: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
) -> dict[str, Any]:
    token = get_access_token()

    response = requests.request(
        method,
        f"{API_BASE_URL}{path}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
        json=json,
        params=params,
        timeout=30,
    )

    if response.status_code == 401:
        raise RuntimeError("Google Fit lehnt den Token ab. Bitte GOOGLE_FIT_ACCESS_TOKEN pruefen.")
    if response.status_code == 403:
        raise RuntimeError("Der Token hat fuer diese Google-Fit-Daten keinen passenden Scope.")
    if response.status_code == 429:
        raise RuntimeError("Google Fit Rate Limit erreicht. Bitte spaeter erneut versuchen.")

    response.raise_for_status()
    return response.json()


def get_access_token() -> str:
    load_client_config()
    if ACCESS_TOKEN and not token_is_expired():
        return ACCESS_TOKEN

    if REFRESH_TOKEN and CLIENT_ID and CLIENT_SECRET:
        return refresh_access_token()

    raise RuntimeError(
        "GOOGLE_FIT_ACCESS_TOKEN fehlt oder ist abgelaufen. "
        "Bitte in der Sidebar mit Google Fit verbinden."
    )


def token_is_expired() -> bool:
    if not TOKEN_EXPIRES_AT:
        return False
    try:
        return int(TOKEN_EXPIRES_AT) <= int(time_module.time()) + 60
    except ValueError:
        return True


def refresh_access_token() -> str:
    global ACCESS_TOKEN, TOKEN_EXPIRES_AT

    response = requests.post(
        TOKEN_URL,
        data={
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
            "refresh_token": REFRESH_TOKEN,
            "grant_type": "refresh_token",
        },
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    save_token_payload(payload)
    return ACCESS_TOKEN


@st.cache_data(ttl=900, show_spinner=False)
def list_data_sources() -> pd.DataFrame:
    payload = request_google_fit("GET", "/users/me/dataSources")
    rows = []
    for source in payload.get("dataSource", []):
        data_type = source.get("dataType", {})
        rows.append(
            {
                "data_stream_id": source.get("dataStreamId"),
                "data_type": data_type.get("name"),
                "type": source.get("type"),
                "application": source.get("application", {}).get("name"),
                "device": source.get("device", {}).get("model"),
            }
        )
    return pd.DataFrame(rows)


@st.cache_data(ttl=900, show_spinner=False)
def get_daily_aggregates(
    selected: list[str],
    start_date: date,
    end_date: date,
    timezone_id: str,
) -> pd.DataFrame:
    request_body = {
        "startTimeMillis": millis_at_start_of_day(start_date),
        "endTimeMillis": millis_at_end_of_day(end_date),
        "aggregateBy": [{"dataTypeName": AGGREGATES[label]["data_type"]} for label in selected],
        "bucketByTime": {
            "period": {
                "type": "day",
                "value": 1,
                "timeZoneId": timezone_id,
            }
        },
    }
    payload = request_google_fit("POST", "/users/me/dataset:aggregate", json=request_body)
    return aggregate_response_to_frame(payload, selected)


def millis_at_start_of_day(value: date) -> int:
    return int(datetime.combine(value, datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def millis_at_end_of_day(value: date) -> int:
    return int(datetime.combine(value + timedelta(days=1), datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def aggregate_response_to_frame(payload: dict[str, Any], selected: list[str]) -> pd.DataFrame:
    rows = []
    for bucket in payload.get("bucket", []):
        day = pd.to_datetime(int(bucket["startTimeMillis"]), unit="ms", utc=True).date()
        row: dict[str, Any] = {"day": day}

        for label, dataset in zip(selected, bucket.get("dataset", [])):
            columns = AGGREGATES[label]["columns"]
            points = dataset.get("point", [])
            if not points:
                for column in columns:
                    row[column] = None
                continue

            values = flatten_point_values(points[0].get("value", []))
            for column, value in zip(columns, values):
                row[column] = value

        rows.append(row)

    frame = pd.DataFrame(rows)
    if frame.empty:
        return frame

    frame["day"] = pd.to_datetime(frame["day"])
    return frame.sort_values("day")


def flatten_point_values(values: list[dict[str, Any]]) -> list[Any]:
    flattened = []
    for value in values:
        if "intVal" in value:
            flattened.append(value["intVal"])
        elif "fpVal" in value:
            flattened.append(value["fpVal"])
        elif "mapVal" in value:
            flattened.append(value["mapVal"])
        elif "stringVal" in value:
            flattened.append(value["stringVal"])
        else:
            flattened.append(None)
    return flattened


def format_value(value: Any, suffix: str = "") -> str:
    if value is None or pd.isna(value):
        return "-"
    if isinstance(value, float):
        return f"{value:,.1f}{suffix}"
    return f"{value}{suffix}"


def render_data_sources() -> None:
    st.subheader("Datenquellen")
    with st.spinner("Google-Fit-Datenquellen werden geladen..."):
        sources = list_data_sources()

    if sources.empty:
        st.info("Keine Datenquellen gefunden.")
        return

    st.dataframe(sources, use_container_width=True, hide_index=True)


def render_aggregates(selected: list[str], start_date: date, end_date: date, timezone_id: str) -> None:
    st.subheader("Tageswerte")
    with st.spinner("Google-Fit-Tageswerte werden geladen..."):
        frame = get_daily_aggregates(selected, start_date, end_date, timezone_id)

    if frame.empty:
        st.info("Keine Tageswerte fuer diesen Zeitraum gefunden.")
        return

    latest = frame.iloc[-1]
    metric_columns = [column for label in selected for column in AGGREGATES[label]["columns"] if column in frame.columns]
    columns = st.columns(min(len(metric_columns), 5))
    for index, column in enumerate(metric_columns[:5]):
        suffix = " m" if column == "distance_m" else ""
        suffix = " bpm" if column.startswith("heart_rate") else suffix
        suffix = " kg" if column.startswith("weight") else suffix
        columns[index].metric(column.replace("_", " ").title(), format_value(latest.get(column), suffix))

    chart_columns = [column for column in metric_columns if pd.api.types.is_numeric_dtype(frame[column])]
    if chart_columns:
        st.line_chart(frame.set_index("day")[chart_columns])
    st.dataframe(frame, use_container_width=True, hide_index=True)


def main() -> None:
    st.set_page_config(page_title="Google Fit Test", page_icon="G", layout="wide")
    handle_oauth_callback()
    st.title("Google Fit Test")

    today = date.today()
    default_start = today - timedelta(days=14)

    with st.sidebar:
        st.header("Zeitraum")
        start = st.date_input("Start", value=default_start, max_value=today)
        end = st.date_input("Ende", value=today, max_value=today)
        timezone_id = st.text_input("Timezone", value="Europe/Berlin")
        selected = st.multiselect(
            "Daten",
            options=list(AGGREGATES.keys()),
            default=["Steps", "Calories", "Distance"],
        )
        show_sources = st.checkbox("Datenquellen anzeigen", value=True)
        refresh = st.button("Neu laden")
        st.divider()
        auth_url = build_auth_url()
        if ACCESS_TOKEN or REFRESH_TOKEN:
            st.success("Google Fit verbunden")
            if auth_url:
                st.link_button("Google Fit neu verbinden", auth_url, use_container_width=True)
        else:
            if auth_url:
                st.link_button("Mit Google Fit verbinden", auth_url, use_container_width=True)
            else:
                st.error("OAuth Client fehlt: client_secret_*.json nicht gefunden.")

    st.caption(
        "Hinweis: Google Fit iOS und Android Health Connect sind verschiedene Datenebenen. "
        "Diese App liest die alte Google-Fit-REST-API; Health-Connect-Daten erscheinen nur, "
        "wenn Google/Fitbit sie auch in diese Google-Fit-Schicht synchronisieren."
    )

    if not ACCESS_TOKEN and not REFRESH_TOKEN:
        st.info("Bitte zuerst in der Sidebar mit Google Fit verbinden.")
        st.stop()

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    try:
        if show_sources:
            render_data_sources()
        if selected:
            render_aggregates(selected, start, end, timezone_id)
    except requests.HTTPError as exc:
        detail = exc.response.text if exc.response is not None else str(exc)
        st.error(f"Google Fit API Fehler: {detail}")
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
