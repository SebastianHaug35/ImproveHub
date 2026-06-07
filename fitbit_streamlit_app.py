from __future__ import annotations

import os
import secrets
import time as time_module
import urllib.parse
from datetime import date, datetime, time as datetime_time, timedelta, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd
import requests
import streamlit as st
from dotenv import load_dotenv, set_key
import altair as alt

try:
    import firebase_admin
    from firebase_admin import credentials, firestore
except ImportError:
    firebase_admin = None
    credentials = None
    firestore = None


load_dotenv()

FITBIT_API_BASE_URL = os.getenv("FITBIT_API_BASE_URL", "https://api.fitbit.com").rstrip("/")
FITBIT_ACCESS_TOKEN = os.getenv("FITBIT_ACCESS_TOKEN")

GOOGLE_HEALTH_API_BASE_URL = os.getenv("GOOGLE_HEALTH_API_BASE_URL", "https://health.googleapis.com/v4").rstrip("/")
GOOGLE_FIT_API_BASE_URL = GOOGLE_HEALTH_API_BASE_URL
GOOGLE_FIT_ACCESS_TOKEN = os.getenv("GOOGLE_FIT_ACCESS_TOKEN")
GOOGLE_FIT_REFRESH_TOKEN = os.getenv("GOOGLE_FIT_REFRESH_TOKEN")
GOOGLE_FIT_CLIENT_ID = os.getenv("GOOGLE_FIT_CLIENT_ID")
GOOGLE_FIT_CLIENT_SECRET = os.getenv("GOOGLE_FIT_CLIENT_SECRET")
GOOGLE_FIT_REDIRECT_URI = os.getenv("GOOGLE_FIT_REDIRECT_URI", "http://localhost:8501")
GOOGLE_FIT_TOKEN_EXPIRES_AT = os.getenv("GOOGLE_FIT_TOKEN_EXPIRES_AT")

FIREBASE_SERVICE_ACCOUNT = os.getenv(
    "FIREBASE_SERVICE_ACCOUNT_FILE",
    "time-tracking-61b9b-firebase-adminsdk-fbsvc-5d690a80c7.json",
)
FIREBASE_HEALTH_USER_ID = os.getenv("FIREBASE_HEALTH_USER_ID", "default")
FIREBASE_HEALTH_COLLECTION = os.getenv("FIREBASE_HEALTH_COLLECTION", "healthConnectUsers")
DISPLAY_TIMEZONE = os.getenv("HEALTH_DISPLAY_TIMEZONE", "Europe/Berlin")

GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
GOOGLE_TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo"
GOOGLE_FIT_OAUTH_STATE_KEY = "GOOGLE_FIT_OAUTH_STATE"
GOOGLE_HEALTH_SCOPES = [
    "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
    "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
    "https://www.googleapis.com/auth/googlehealth.location.readonly",
    "https://www.googleapis.com/auth/googlehealth.settings.readonly",
    "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
    "https://www.googleapis.com/auth/googlehealth.profile.readonly",
]
GOOGLE_FIT_SCOPES = GOOGLE_HEALTH_SCOPES

FITBIT_ACTIVITY_RESOURCES = {
    "Steps": "steps",
    "Calories": "calories",
    "Distance": "distance",
    "Floors": "floors",
    "Elevation": "elevation",
}

GOOGLE_HEALTH_AGGREGATES = {
    "Steps": {
        "data_type": "steps",
        "columns": ["steps"],
        "extract": lambda rollup: int(rollup.get("steps", {}).get("countSum") or 0),
    },
    "Active Minutes": {
        "data_type": "active-minutes",
        "columns": ["active_minutes"],
        "extract": lambda rollup: sum(
            int(item.get("activeMinutesSum") or 0)
            for item in rollup.get("activeMinutes", {}).get("activeMinutesRollupByActivityLevel", [])
        ),
    },
    "Calories": {
        "data_type": "active-energy-burned",
        "columns": ["calories"],
        "extract": lambda rollup: float(rollup.get("activeEnergyBurned", {}).get("kcalSum") or 0.0),
    },
    "Distance": {
        "data_type": "distance",
        "columns": ["distance_m"],
        "extract": lambda rollup: float(rollup.get("distance", {}).get("millimetersSum") or 0.0) / 1000.0,
    },
    "Heart Rate": {
        "data_type": "heart-rate",
        "columns": ["heart_rate_avg", "heart_rate_max", "heart_rate_min"],
        "extract": lambda rollup: [
            rollup.get("heartRate", {}).get("beatsPerMinuteAvg"),
            rollup.get("heartRate", {}).get("beatsPerMinuteMax"),
            rollup.get("heartRate", {}).get("beatsPerMinuteMin"),
        ],
    },
    "Weight": {
        "data_type": "weight",
        "columns": ["weight_avg", "weight_max", "weight_min"],
        "extract": lambda rollup: [
            (rollup.get("weight", {}).get("weightGramsAvg") or 0.0) / 1000.0,
            None,
            None,
        ],
    },
}

GOOGLE_FIT_AGGREGATES = GOOGLE_HEALTH_AGGREGATES


def json_load(path: Path) -> dict[str, Any]:
    import json

    return json.loads(path.read_text(encoding="utf-8"))


def load_google_client_config() -> None:
    global GOOGLE_FIT_CLIENT_ID, GOOGLE_FIT_CLIENT_SECRET

    if GOOGLE_FIT_CLIENT_ID and GOOGLE_FIT_CLIENT_SECRET:
        return

    client_file = os.getenv("GOOGLE_FIT_CLIENT_SECRET_FILE")
    candidates = [Path(client_file)] if client_file else sorted(Path(".").glob("client_secret_*.json"))
    if not candidates:
        return

    payload = json_load(candidates[0])
    config = payload.get("installed") or payload.get("web") or {}
    GOOGLE_FIT_CLIENT_ID = config.get("client_id")
    GOOGLE_FIT_CLIENT_SECRET = config.get("client_secret")


def build_google_auth_url() -> str:
    load_google_client_config()
    if not GOOGLE_FIT_CLIENT_ID:
        return ""

    state = st.session_state.get("google_fit_oauth_state") or os.getenv(GOOGLE_FIT_OAUTH_STATE_KEY)
    if not state:
        state = secrets.token_urlsafe(24)
        set_key(".env", GOOGLE_FIT_OAUTH_STATE_KEY, state)
    st.session_state["google_fit_oauth_state"] = state
    params = {
        "client_id": GOOGLE_FIT_CLIENT_ID,
        "redirect_uri": GOOGLE_FIT_REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(GOOGLE_FIT_SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{GOOGLE_AUTH_URL}?{urllib.parse.urlencode(params)}"


def handle_google_oauth_callback() -> None:
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
            os.getenv(GOOGLE_FIT_OAUTH_STATE_KEY),
        ]
        if value
    }
    if state not in expected_states:
        st.error("Google OAuth State passt nicht. Bitte Login erneut starten.")
        return

    load_google_client_config()
    if not GOOGLE_FIT_CLIENT_ID or not GOOGLE_FIT_CLIENT_SECRET:
        st.error("OAuth Client fehlt. Lege client_secret_*.json in den Projektordner.")
        return

    response = requests.post(
        GOOGLE_TOKEN_URL,
        data={
            "client_id": GOOGLE_FIT_CLIENT_ID,
            "client_secret": GOOGLE_FIT_CLIENT_SECRET,
            "redirect_uri": GOOGLE_FIT_REDIRECT_URI,
            "code": code,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    response.raise_for_status()
    save_google_token_payload(response.json())
    st.session_state.pop("google_fit_oauth_state", None)
    set_key(".env", GOOGLE_FIT_OAUTH_STATE_KEY, "")
    st.query_params.clear()
    st.cache_data.clear()
    st.success("Google Health API verbunden.")
    st.rerun()


def save_google_token_payload(payload: dict[str, Any]) -> None:
    global GOOGLE_FIT_ACCESS_TOKEN, GOOGLE_FIT_REFRESH_TOKEN, GOOGLE_FIT_TOKEN_EXPIRES_AT

    GOOGLE_FIT_ACCESS_TOKEN = payload["access_token"]
    GOOGLE_FIT_TOKEN_EXPIRES_AT = str(int(time_module.time()) + int(payload.get("expires_in", 3600)) - 60)
    set_key(".env", "GOOGLE_FIT_ACCESS_TOKEN", GOOGLE_FIT_ACCESS_TOKEN)
    set_key(".env", "GOOGLE_FIT_TOKEN_EXPIRES_AT", GOOGLE_FIT_TOKEN_EXPIRES_AT)

    if payload.get("refresh_token"):
        GOOGLE_FIT_REFRESH_TOKEN = payload["refresh_token"]
        set_key(".env", "GOOGLE_FIT_REFRESH_TOKEN", GOOGLE_FIT_REFRESH_TOKEN)


def clear_google_health_tokens() -> None:
    global GOOGLE_FIT_ACCESS_TOKEN, GOOGLE_FIT_REFRESH_TOKEN, GOOGLE_FIT_TOKEN_EXPIRES_AT

    GOOGLE_FIT_ACCESS_TOKEN = None
    GOOGLE_FIT_REFRESH_TOKEN = None
    GOOGLE_FIT_TOKEN_EXPIRES_AT = None
    for key in [
        "GOOGLE_FIT_ACCESS_TOKEN",
        "GOOGLE_FIT_REFRESH_TOKEN",
        "GOOGLE_FIT_TOKEN_EXPIRES_AT",
        GOOGLE_FIT_OAUTH_STATE_KEY,
    ]:
        os.environ.pop(key, None)
    set_key(".env", "GOOGLE_FIT_ACCESS_TOKEN", "")
    set_key(".env", "GOOGLE_FIT_REFRESH_TOKEN", "")
    set_key(".env", "GOOGLE_FIT_TOKEN_EXPIRES_AT", "")
    set_key(".env", GOOGLE_FIT_OAUTH_STATE_KEY, "")
    st.session_state.pop("google_fit_oauth_state", None)
    st.cache_data.clear()


def get_google_token_info() -> dict[str, Any] | None:
    token = GOOGLE_FIT_ACCESS_TOKEN or GOOGLE_FIT_REFRESH_TOKEN
    if not token:
        return None

    response = requests.get(GOOGLE_TOKEN_INFO_URL, params={"access_token": GOOGLE_FIT_ACCESS_TOKEN}, timeout=30)
    if response.status_code != 200:
        return {"error": response.text, "status_code": response.status_code}
    return response.json()


def google_token_is_expired() -> bool:
    if not GOOGLE_FIT_TOKEN_EXPIRES_AT:
        return False
    try:
        return int(GOOGLE_FIT_TOKEN_EXPIRES_AT) <= int(time_module.time()) + 60
    except ValueError:
        return True


def refresh_google_access_token() -> str:
    global GOOGLE_FIT_ACCESS_TOKEN

    response = requests.post(
        GOOGLE_TOKEN_URL,
        data={
            "client_id": GOOGLE_FIT_CLIENT_ID,
            "client_secret": GOOGLE_FIT_CLIENT_SECRET,
            "refresh_token": GOOGLE_FIT_REFRESH_TOKEN,
            "grant_type": "refresh_token",
        },
        timeout=30,
    )
    response.raise_for_status()
    save_google_token_payload(response.json())
    return GOOGLE_FIT_ACCESS_TOKEN


def get_google_access_token() -> str:
    load_google_client_config()
    if GOOGLE_FIT_ACCESS_TOKEN and not google_token_is_expired():
        return GOOGLE_FIT_ACCESS_TOKEN
    if GOOGLE_FIT_REFRESH_TOKEN and GOOGLE_FIT_CLIENT_ID and GOOGLE_FIT_CLIENT_SECRET:
        return refresh_google_access_token()
    raise RuntimeError("Google Health API ist noch nicht verbunden.")


def request_google_fit(
    method: str,
    path: str,
    *,
    json: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
) -> dict[str, Any]:
    token = get_google_access_token()
    response = requests.request(
        method,
        f"{GOOGLE_HEALTH_API_BASE_URL}{path}",
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
        raise RuntimeError("Google Health API lehnt den Token ab. Bitte erneut verbinden.")
    if response.status_code == 403:
        detail = response.text.strip()
        hint = f" Details: {detail}" if detail else ""
        raise RuntimeError("Der Token hat fuer diese Google-Health-Daten keinen passenden Scope." + hint)
    response.raise_for_status()
    return response.json()


def request_google_health(
    method: str,
    path: str,
    *,
    json: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return request_google_fit(method, path, json=json, params=params)


def request_fitbit(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    if not FITBIT_ACCESS_TOKEN:
        raise RuntimeError("FITBIT_ACCESS_TOKEN fehlt in der .env.")

    response = requests.get(
        f"{FITBIT_API_BASE_URL}{path}",
        headers={
            "Authorization": f"Bearer {FITBIT_ACCESS_TOKEN}",
            "Accept": "application/json",
        },
        params=params,
        timeout=30,
    )

    if response.status_code == 401:
        raise RuntimeError("Fitbit lehnt den Token ab. Bitte FITBIT_ACCESS_TOKEN pruefen.")
    if response.status_code == 403:
        raise RuntimeError("Der Token hat fuer diese Fitbit-Daten keinen passenden Scope.")
    if response.status_code == 429:
        reset = response.headers.get("fitbit-rate-limit-reset")
        hint = f" Reset in ca. {reset} Sekunden." if reset else ""
        raise RuntimeError(f"Fitbit Rate Limit erreicht.{hint}")

    response.raise_for_status()
    return response.json()


@st.cache_data(ttl=900, show_spinner=False)
def get_google_fit_sources() -> pd.DataFrame:
    payload = request_google_health("GET", "/users/me/pairedDevices")
    rows = []
    for source in payload.get("pairedDevices", []):
        rows.append(
            {
                "device_name": source.get("device", {}).get("displayName"),
                "form_factor": source.get("device", {}).get("formFactor"),
                "manufacturer": source.get("device", {}).get("manufacturer"),
                "platform": source.get("platform"),
                "device_id": source.get("name"),
            }
        )
    return pd.DataFrame(rows)


@st.cache_data(ttl=900, show_spinner=False)
def get_google_fit_daily_aggregates(selected: list[str], start_date: date, end_date: date, timezone_id: str) -> pd.DataFrame:
    rows = []
    for day_value in date_range(start_date, end_date):
        row: dict[str, Any] = {"day": day_value}
        for label in selected:
            config = GOOGLE_HEALTH_AGGREGATES[label]
            payload = request_google_health(
                "POST",
                f"/users/me/dataTypes/{config['data_type']}/dataPoints:dailyRollUp",
                json={
                    "range": {
                        "start": {"date": to_health_date(day_value)},
                        "end": {"date": to_health_date(day_value + timedelta(days=1))},
                    },
                    "windowSizeDays": 1,
                    "pageSize": 1,
                },
            )
            rollup_points = payload.get("rollupDataPoints", [])
            if not rollup_points:
                for column in config["columns"]:
                    row[column] = None
                continue

            values = config["extract"](rollup_points[0])
            if not isinstance(values, list):
                values = [values]
            for column, value in zip(config["columns"], values):
                row[column] = value
        rows.append(row)
    return normalize_frame(rows)


def to_health_date(value: date) -> dict[str, int]:
    return {"year": value.year, "month": value.month, "day": value.day}


def date_range(start_date: date, end_date: date) -> list[date]:
    values = []
    current = start_date
    while current <= end_date:
        values.append(current)
        current += timedelta(days=1)
    return values


@st.cache_data(ttl=900, show_spinner=False)
def get_google_health_profile() -> dict[str, Any]:
    return request_google_health("GET", "/users/me/profile")


@st.cache_data(ttl=900, show_spinner=False)
def get_profile() -> dict[str, Any]:
    return request_fitbit("/1/user/-/profile.json")


@st.cache_data(ttl=900, show_spinner=False)
def get_activity_series(resource: str, start_date: date, end_date: date) -> pd.DataFrame:
    payload = request_fitbit(
        f"/1/user/-/activities/{resource}/date/{start_date.isoformat()}/{end_date.isoformat()}.json"
    )
    rows = payload.get(f"activities-{resource}", [])
    return normalize_time_series(rows, "dateTime", resource)


@st.cache_data(ttl=900, show_spinner=False)
def get_heart_rate(start_date: date, end_date: date) -> pd.DataFrame:
    payload = request_fitbit(
        f"/1/user/-/activities/heart/date/{start_date.isoformat()}/{end_date.isoformat()}.json"
    )
    rows: list[dict[str, Any]] = []
    for row in payload.get("activities-heart", []):
        value = row.get("value", {})
        rows.append({"day": row.get("dateTime"), "resting_heart_rate": value.get("restingHeartRate")})
    return normalize_frame(rows)


@st.cache_data(ttl=900, show_spinner=False)
def get_sleep(start_date: date, end_date: date) -> pd.DataFrame:
    payload = request_fitbit(f"/1.2/user/-/sleep/date/{start_date.isoformat()}/{end_date.isoformat()}.json")
    rows: list[dict[str, Any]] = []
    for row in payload.get("sleep", []):
        levels_summary = row.get("levels", {}).get("summary", {})
        rows.append(
            {
                "day": row.get("dateOfSleep"),
                "start_time": row.get("startTime"),
                "end_time": row.get("endTime"),
                "duration_minutes": millis_to_minutes(row.get("duration")),
                "efficiency": row.get("efficiency"),
                "minutes_asleep": row.get("minutesAsleep"),
                "minutes_awake": row.get("minutesAwake"),
                "time_in_bed": row.get("timeInBed"),
                "deep_minutes": nested_minutes(levels_summary, "deep"),
                "light_minutes": nested_minutes(levels_summary, "light"),
                "rem_minutes": nested_minutes(levels_summary, "rem"),
                "wake_minutes": nested_minutes(levels_summary, "wake"),
                "is_main_sleep": row.get("isMainSleep"),
            }
        )
    return normalize_frame(rows)


@st.cache_data(ttl=900, show_spinner=False)
def get_health_connect_daily(start_date: date, end_date: date) -> pd.DataFrame:
    db = get_firestore_client()
    if db is None:
        return pd.DataFrame()

    docs = (
        db.collection(FIREBASE_HEALTH_COLLECTION)
        .document(FIREBASE_HEALTH_USER_ID)
        .collection("healthConnectDaily")
        .where("day", ">=", start_date.isoformat())
        .where("day", "<=", end_date.isoformat())
        .stream()
    )

    rows: list[dict[str, Any]] = []
    for doc in docs:
        payload = doc.to_dict() or {}
        metrics = payload.get("metrics", {})
        rows.append(
            {
                "day": payload.get("day"),
                "steps": metrics.get("steps"),
                "distanceMeters": metrics.get("distanceMeters"),
                "distanceSamples": metrics.get("distanceSamples"),
                "activeCaloriesKcal": metrics.get("activeCaloriesKcal"),
                "activeCaloriesSamples": metrics.get("activeCaloriesSamples"),
                "totalCaloriesKcal": metrics.get("totalCaloriesKcal"),
                "totalCaloriesSamples": metrics.get("totalCaloriesSamples"),
                "sleepMinutes": metrics.get("sleepMinutes"),
                "sleepSessionCount": metrics.get("sleepSessionCount"),
                "latestSleepStart": metrics.get("latestSleepStart"),
                "latestSleepEnd": metrics.get("latestSleepEnd"),
                "sleepAwakeMinutes": metrics.get("sleepAwakeMinutes"),
                "sleepLightMinutes": metrics.get("sleepLightMinutes"),
                "sleepDeepMinutes": metrics.get("sleepDeepMinutes"),
                "sleepRemMinutes": metrics.get("sleepRemMinutes"),
                "sleepUnknownMinutes": metrics.get("sleepUnknownMinutes"),
                "heartRateAvgBpm": metrics.get("heartRateAvgBpm"),
                "heartRateRecordCount": metrics.get("heartRateRecordCount"),
                "restingHeartRateAvgBpm": metrics.get("restingHeartRateAvgBpm"),
                "restingHeartRateRecordCount": metrics.get("restingHeartRateRecordCount"),
                "hrvRmssdAvgMillis": metrics.get("hrvRmssdAvgMillis"),
                "hrvRecordCount": metrics.get("hrvRecordCount"),
                "weightLatestKg": metrics.get("weightLatestKg"),
                "weightRecordCount": metrics.get("weightRecordCount"),
                "sourceApps": ", ".join(metrics.get("sourceApps", [])),
                "sleepSessions": metrics.get("sleepSessions", []),
                "provider": payload.get("provider"),
                "exportedAt": payload.get("exportedAt"),
            }
        )
    return normalize_frame(rows)


def get_firestore_client() -> Any | None:
    if firebase_admin is None or credentials is None or firestore is None:
        return None

    service_account_path = os.path.abspath(FIREBASE_SERVICE_ACCOUNT)
    if not os.path.exists(service_account_path):
        return None

    app_name = "health-dashboard"
    try:
        app = firebase_admin.get_app(app_name)
    except ValueError:
        app = firebase_admin.initialize_app(credentials.Certificate(service_account_path), name=app_name)
    return firestore.client(app=app)


def normalize_time_series(rows: list[dict[str, Any]], date_column: str, value_column: str) -> pd.DataFrame:
    frame = pd.DataFrame(rows)
    if frame.empty:
        return frame
    frame = frame.rename(columns={date_column: "day", "value": value_column})
    frame[value_column] = pd.to_numeric(frame[value_column], errors="coerce")
    return normalize_frame(frame.to_dict("records"))


def normalize_frame(rows: list[dict[str, Any]]) -> pd.DataFrame:
    frame = pd.DataFrame(rows)
    if frame.empty:
        return frame
    if "day" in frame.columns:
        frame["day"] = pd.to_datetime(frame["day"])
        frame = frame.sort_values("day")
    return frame


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


def millis_at_start_of_day(value: date) -> int:
    return int(datetime.combine(value, datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def millis_at_end_of_day(value: date) -> int:
    return int(datetime.combine(value + timedelta(days=1), datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def millis_to_minutes(value: Any) -> float | None:
    if value is None:
        return None
    return round(float(value) / 60000, 1)


def nested_minutes(summary: dict[str, Any], stage: str) -> Any:
    value = summary.get(stage, {})
    if isinstance(value, dict):
        return value.get("minutes")
    return None


def format_value(value: Any, suffix: str = "") -> str:
    if value is None or pd.isna(value):
        return "-"
    if isinstance(value, float):
        return f"{value:,.1f}{suffix}"
    return f"{value}{suffix}"


def render_google_fit(start_date: date, end_date: date, timezone_id: str, selected: list[str], show_sources: bool) -> None:
    st.subheader("Google Health API")

    token_info = get_google_token_info()
    with st.expander("OAuth Debug", expanded=False):
        st.write({
            "has_access_token": bool(GOOGLE_FIT_ACCESS_TOKEN),
            "has_refresh_token": bool(GOOGLE_FIT_REFRESH_TOKEN),
            "token_expires_at": GOOGLE_FIT_TOKEN_EXPIRES_AT,
            "token_info": token_info,
        })

    if not GOOGLE_FIT_ACCESS_TOKEN and not GOOGLE_FIT_REFRESH_TOKEN:
        st.info("Google Health API ist noch nicht verbunden.")
        auth_url = build_google_auth_url()
        if auth_url:
            st.link_button("Mit Google Health API verbinden", auth_url, use_container_width=False)
        else:
            st.warning("client_secret_*.json fehlt. Ohne OAuth-Client kann kein Google-Login gestartet werden.")
        return

    if show_sources:
        sources = get_google_fit_sources()
        with st.expander("Google-Health-Datenquellen", expanded=False):
            if sources.empty:
                st.info("Keine Google-Health-Datenquellen gefunden.")
            else:
                st.dataframe(sources, use_container_width=True, hide_index=True)

    frame = get_google_fit_daily_aggregates(selected, start_date, end_date, timezone_id)
    if frame.empty:
        st.info("Keine Google-Health-Tageswerte fuer diesen Zeitraum gefunden.")
        return

    latest = frame.iloc[-1]
    metric_columns = [column for label in selected for column in GOOGLE_FIT_AGGREGATES[label]["columns"] if column in frame.columns]
    columns = st.columns(min(max(len(metric_columns), 1), 5))
    for index, column in enumerate(metric_columns[:5]):
        suffix = " m" if column == "distance_m" else ""
        suffix = " bpm" if column.startswith("heart_rate") else suffix
        suffix = " kg" if column.startswith("weight") else suffix
        columns[index].metric(column.replace("_", " ").title(), format_value(latest.get(column), suffix))

    chart_columns = [column for column in metric_columns if pd.api.types.is_numeric_dtype(frame[column])]
    if chart_columns:
        st.line_chart(frame.set_index("day")[chart_columns])
    st.dataframe(frame, use_container_width=True, hide_index=True)


def render_fitbit(start_date: date, end_date: date, selected_activity: list[str], show_heart: bool, show_sleep: bool) -> None:
    st.subheader("Fitbit API")
    if not FITBIT_ACCESS_TOKEN:
        st.caption("Fitbit ist optional. Gerade wird dieser Kanal nicht genutzt, weil kein FITBIT_ACCESS_TOKEN gesetzt ist.")
        return

    profile = get_profile()
    user = profile.get("user", {})
    profile_columns = st.columns(5)
    profile_values = [
        ("Name", user.get("fullName") or user.get("displayName"), ""),
        ("Alter", user.get("age"), ""),
        ("Groesse", user.get("height"), ""),
        ("Gewicht", user.get("weight"), ""),
        ("Zeitzone", user.get("timezone"), ""),
    ]
    for column, (label, value, suffix) in zip(profile_columns, profile_values):
        column.metric(label, format_value(value, suffix))

    if selected_activity:
        frames = []
        for label in selected_activity:
            resource = FITBIT_ACTIVITY_RESOURCES[label]
            frame = get_activity_series(resource, start_date, end_date)
            if not frame.empty:
                frames.append(frame.set_index("day"))
        if frames:
            activity = pd.concat(frames, axis=1).reset_index()
            st.line_chart(activity.set_index("day")[[FITBIT_ACTIVITY_RESOURCES[label] for label in selected_activity if FITBIT_ACTIVITY_RESOURCES[label] in activity.columns]])
            st.dataframe(activity, use_container_width=True, hide_index=True)

    if show_heart:
        heart = get_heart_rate(start_date, end_date)
        if not heart.empty and "resting_heart_rate" in heart.columns:
            st.metric("Fitbit Ruhepuls", format_value(heart.iloc[-1].get("resting_heart_rate"), " bpm"))

    if show_sleep:
        sleep = get_sleep(start_date, end_date)
        if not sleep.empty:
            visible = sleep[sleep.get("is_main_sleep", False) == True]
            visible = visible if not visible.empty else sleep
            st.dataframe(visible, use_container_width=True, hide_index=True)


def render_health_connect(start_date: date, end_date: date) -> None:
    st.subheader("Health Connect")
    frame = get_health_connect_daily(start_date, end_date)
    if frame.empty:
        st.info("Keine Health-Connect-Daten in Firebase gefunden.")
        st.caption("Dieser Bereich zeigt die Android-Daten aus Firestore, nicht die Google-Health-API.")
        return

    latest = frame.iloc[-1]
    metric_specs = [
        ("Schritte", "steps", ""),
        ("Distanz", "distanceMeters", " m"),
        ("Aktive Kalorien", "activeCaloriesKcal", " kcal"),
        ("Schlaf", "sleepMinutes", " min"),
        ("Ruhepuls", "restingHeartRateAvgBpm", " bpm"),
    ]
    columns = st.columns(len(metric_specs))
    for column, (label, key, suffix) in zip(columns, metric_specs):
        column.metric(label, format_value(latest.get(key), suffix))

    plot_groups = [
        ("Fitness", ["steps"]),
        ("Distanz", ["distanceMeters"]),
        ("Kalorien", ["activeCaloriesKcal", "totalCaloriesKcal"]),
        ("Schlaf", ["sleepMinutes", "sleepLightMinutes", "sleepDeepMinutes", "sleepRemMinutes", "sleepAwakeMinutes"]),
    ]
    for title, columns_for_plot in plot_groups:
        valid_columns = [
            column
            for column in columns_for_plot
            if column in frame.columns and pd.api.types.is_numeric_dtype(frame[column])
        ]
        if valid_columns:
            st.markdown(f"**{title}**")
            st.line_chart(frame.set_index("day")[valid_columns])

    if "sleepSessions" in frame.columns:
        sleep_rows = []
        for _, row in frame.iterrows():
            for session in row.get("sleepSessions", []) or []:
                for stage in session.get("stages", []) or []:
                    sleep_rows.append(
                        {
                            "day": row.get("day"),
                            "session_start": session.get("start"),
                            "session_end": session.get("end"),
                            "stage": stage.get("stage"),
                            "stage_start": stage.get("start"),
                            "stage_end": stage.get("end"),
                            "minutes": stage.get("minutes"),
                        }
                    )
        if sleep_rows:
            sleep_stage_frame = pd.DataFrame(sleep_rows)
            st.markdown("**Schlafphasen**")
            stage_timeline = build_sleep_stage_timeline(sleep_stage_frame)
            if not stage_timeline.empty:
                st.markdown("**Schlafphasen ueber alle Tage**")
                chart = (
                    alt.Chart(stage_timeline)
                    .mark_bar(size=18)
                    .encode(
                        x=alt.X(
                            "start_hour:Q",
                            title="Uhrzeit",
                            scale=alt.Scale(domain=[18, 36]),
                            axis=alt.Axis(
                                values=[18, 20, 22, 24, 26, 28, 30, 32, 34, 36],
                                labelExpr="datum.value >= 24 ? datum.value - 24 : datum.value",
                            ),
                        ),
                        x2="end_hour:Q",
                        y=alt.Y("day_label:N", title="Tag", sort=stage_timeline["day_label"].tolist()),
                        color=alt.Color(
                            "stage:N",
                            title="Phase",
                            scale=alt.Scale(
                                domain=["awake", "awake_in_bed", "out_of_bed", "light", "deep", "rem", "sleeping", "unknown"],
                                range=["#ef4444", "#f97316", "#f59e0b", "#60a5fa", "#1d4ed8", "#8b5cf6", "#10b981", "#94a3b8"],
                            ),
                        ),
                        tooltip=[
                            alt.Tooltip("day_label:N", title="Tag"),
                            alt.Tooltip("stage:N", title="Phase"),
                            alt.Tooltip("stage_start:N", title="Von"),
                            alt.Tooltip("stage_end:N", title="Bis"),
                            alt.Tooltip("minutes:Q", title="Minuten"),
                        ],
                    )
                    .properties(height=max(160, len(stage_timeline["day_label"].unique()) * 28))
                )
                st.altair_chart(chart, use_container_width=True)
            st.dataframe(sleep_stage_frame, use_container_width=True, hide_index=True)

    with st.expander("Health-Connect-Debug", expanded=False):
        debug_columns = [
            column
            for column in [
                "day",
                "sleepSessionCount",
                "latestSleepStart",
                "latestSleepEnd",
                "sleepAwakeMinutes",
                "sleepLightMinutes",
                "sleepDeepMinutes",
                "sleepRemMinutes",
                "sleepUnknownMinutes",
                "distanceSamples",
                "activeCaloriesSamples",
                "totalCaloriesSamples",
                "heartRateRecordCount",
                "restingHeartRateRecordCount",
                "hrvRecordCount",
                "weightRecordCount",
                "sourceApps",
                "provider",
                "exportedAt",
            ]
            if column in frame.columns
        ]
        if debug_columns:
            st.dataframe(frame[debug_columns], use_container_width=True, hide_index=True)
    st.dataframe(frame, use_container_width=True, hide_index=True)


def build_sleep_stage_timeline(frame: pd.DataFrame) -> pd.DataFrame:
    if frame.empty:
        return pd.DataFrame()

    timeline = frame.copy()
    timeline["day"] = pd.to_datetime(timeline["day"]).dt.strftime("%Y-%m-%d")
    timeline["stage_start_dt"] = pd.to_datetime(timeline["stage_start"], errors="coerce")
    timeline["stage_end_dt"] = pd.to_datetime(timeline["stage_end"], errors="coerce")
    timeline = timeline.dropna(subset=["stage_start_dt", "stage_end_dt"])
    if timeline.empty:
        return timeline

    target_tz = ZoneInfo(DISPLAY_TIMEZONE)
    if str(timeline["stage_start_dt"].dt.tz) != "None":
        timeline["stage_start_dt"] = timeline["stage_start_dt"].dt.tz_convert(target_tz)
    else:
        timeline["stage_start_dt"] = timeline["stage_start_dt"].dt.tz_localize(target_tz)

    if str(timeline["stage_end_dt"].dt.tz) != "None":
        timeline["stage_end_dt"] = timeline["stage_end_dt"].dt.tz_convert(target_tz)
    else:
        timeline["stage_end_dt"] = timeline["stage_end_dt"].dt.tz_localize(target_tz)

    timeline["start_hour"] = timeline["stage_start_dt"].dt.hour + timeline["stage_start_dt"].dt.minute / 60
    timeline["end_hour"] = timeline["stage_end_dt"].dt.hour + timeline["stage_end_dt"].dt.minute / 60
    timeline["start_hour"] = timeline["start_hour"].apply(normalize_sleep_hour)
    timeline["end_hour"] = timeline["end_hour"].apply(normalize_sleep_hour)
    timeline["day_label"] = timeline["day"]
    timeline["stage_start"] = timeline["stage_start_dt"].dt.strftime("%Y-%m-%d %H:%M")
    timeline["stage_end"] = timeline["stage_end_dt"].dt.strftime("%Y-%m-%d %H:%M")
    timeline = timeline.sort_values(["day_label", "start_hour", "end_hour"])
    return timeline


def normalize_sleep_hour(value: float) -> float:
    return value + 24 if value < 18 else value


def main() -> None:
    st.set_page_config(page_title="Health Dashboard", page_icon="H", layout="wide")
    handle_google_oauth_callback()

    st.title("Health Dashboard")
    st.caption("Google Health API und Health Connect laufen hier nebeneinander. Fitbit bleibt optional.")

    today = date.today()
    default_start = today - timedelta(days=14)

    with st.sidebar:
        st.header("Zeitraum")
        start = st.date_input("Start", value=default_start, max_value=today)
        end = st.date_input("Ende", value=today, max_value=today)
        timezone_id = st.text_input("Timezone", value="Europe/Berlin")
        google_fit_data = st.multiselect(
            "Google Health Daten",
            options=list(GOOGLE_FIT_AGGREGATES.keys()),
            default=["Steps", "Calories", "Distance"],
        )
        fitbit_activity = st.multiselect(
            "Fitbit Aktivitaet",
            options=list(FITBIT_ACTIVITY_RESOURCES.keys()),
            default=["Steps", "Calories", "Distance"],
        )
        show_google_fit_sources = st.checkbox("Google-Health-Datenquellen anzeigen", value=False)
        show_google_fit = st.checkbox("Google Health API", value=True)
        show_health_connect = st.checkbox("Health Connect (Firebase)", value=True)
        show_fitbit = st.checkbox("Fitbit API", value=True)
        show_fitbit_heart = st.checkbox("Fitbit Herzfrequenz", value=True)
        show_fitbit_sleep = st.checkbox("Fitbit Schlaf", value=True)
        refresh = st.button("Neu laden")

        st.divider()
        auth_url = build_google_auth_url()
        if GOOGLE_FIT_ACCESS_TOKEN or GOOGLE_FIT_REFRESH_TOKEN:
            st.success("Google Health API verbunden")
            if auth_url:
                st.link_button("Google Health API neu verbinden", auth_url, use_container_width=True)
            if st.button("Google Health API abmelden", use_container_width=True):
                clear_google_health_tokens()
                st.rerun()
        else:
            if auth_url:
                st.link_button("Mit Google Health API verbinden", auth_url, use_container_width=True)
            else:
                st.info("Google OAuth ist noch nicht konfiguriert.")

        if FITBIT_ACCESS_TOKEN:
            st.success("Fitbit Token gesetzt")
        else:
            st.caption("Kein Fitbit-Token gesetzt.")

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    st.info(
        "Zur Einordnung: Google Health API liest die Fitbit/Google-Cloud-Daten. "
        "Health Connect zeigt die Android-Daten aus Firebase. Das sind verwandte, aber nicht identische Datenquellen."
    )

    try:
        if show_google_fit:
            render_google_fit(start, end, timezone_id, google_fit_data, show_google_fit_sources)
        if show_health_connect:
            render_health_connect(start, end)
        if show_fitbit:
            render_fitbit(start, end, fitbit_activity, show_fitbit_heart, show_fitbit_sleep)
    except requests.HTTPError as exc:
        detail = exc.response.text if exc.response is not None else str(exc)
        st.error(f"API Fehler: {detail}")
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
