from __future__ import annotations

import os
from datetime import date, timedelta
from typing import Any

import pandas as pd
import requests
import streamlit as st
from dotenv import load_dotenv


load_dotenv()

API_BASE_URL = os.getenv("OURA_API_BASE_URL", "https://api.ouraring.com/v2").rstrip("/")
ACCESS_TOKEN = os.getenv("OURA_ACCESS_TOKEN")


ENDPOINTS = {
    "Sleep": "daily_sleep",
    "Readiness": "daily_readiness",
    "Activity": "daily_activity",
    "Stress": "daily_stress",
    "Spo2": "daily_spo2",
}


def request_oura(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    if not ACCESS_TOKEN:
        raise RuntimeError("OURA_ACCESS_TOKEN fehlt in der .env.")

    response = requests.get(
        f"{API_BASE_URL}{path}",
        headers={"Authorization": f"Bearer {ACCESS_TOKEN}"},
        params=params,
        timeout=30,
    )

    if response.status_code == 401:
        raise RuntimeError("Oura lehnt den Token ab. Bitte OURA_ACCESS_TOKEN pruefen.")
    if response.status_code == 403:
        raise RuntimeError("Der Token hat fuer diese Daten keinen passenden Scope.")
    if response.status_code == 429:
        raise RuntimeError("Oura Rate Limit erreicht. Bitte spaeter erneut versuchen.")

    response.raise_for_status()
    return response.json()


def collect_paginated(endpoint: str, start_date: date, end_date: date) -> list[dict[str, Any]]:
    params: dict[str, Any] = {
        "start_date": start_date.isoformat(),
        "end_date": end_date.isoformat(),
    }
    rows: list[dict[str, Any]] = []

    while True:
        payload = request_oura(f"/usercollection/{endpoint}", params=params)
        rows.extend(payload.get("data", []))

        next_token = payload.get("next_token")
        if not next_token:
            return rows

        params["next_token"] = next_token


@st.cache_data(ttl=900, show_spinner=False)
def get_personal_info() -> dict[str, Any]:
    return request_oura("/usercollection/personal_info")


@st.cache_data(ttl=900, show_spinner=False)
def get_daily_data(endpoint: str, start_date: date, end_date: date) -> pd.DataFrame:
    rows = collect_paginated(endpoint, start_date, end_date)
    if not rows:
        return pd.DataFrame()

    frame = pd.json_normalize(rows, sep="_")
    if "day" in frame.columns:
        frame["day"] = pd.to_datetime(frame["day"])
        frame = frame.sort_values("day")
    return frame


def format_value(value: Any, suffix: str = "") -> str:
    if value is None or pd.isna(value):
        return "-"
    if isinstance(value, float):
        return f"{value:,.1f}{suffix}"
    return f"{value}{suffix}"


def score_columns(frame: pd.DataFrame) -> list[str]:
    preferred = [
        "score",
        "contributors_sleep_balance",
        "contributors_previous_day_activity",
        "contributors_activity_balance",
        "contributors_resting_heart_rate",
        "contributors_hrv_balance",
        "contributors_recovery_index",
        "contributors_body_temperature",
        "contributors_deep_sleep",
        "contributors_efficiency",
        "contributors_latency",
        "contributors_rem_sleep",
        "contributors_timing",
        "contributors_total_sleep",
    ]
    return [column for column in preferred if column in frame.columns]


def metric_columns(frame: pd.DataFrame, endpoint: str) -> list[str]:
    candidates_by_endpoint = {
        "daily_sleep": [
            "score",
            "total_sleep_duration",
            "contributors_deep_sleep",
            "contributors_rem_sleep",
            "contributors_efficiency",
        ],
        "daily_readiness": [
            "score",
            "temperature_deviation",
            "temperature_trend_deviation",
            "contributors_resting_heart_rate",
            "contributors_hrv_balance",
        ],
        "daily_activity": [
            "score",
            "steps",
            "active_calories",
            "total_calories",
            "equivalent_walking_distance",
        ],
        "daily_stress": [
            "stress_high",
            "recovery_high",
            "day_summary",
        ],
        "daily_spo2": [
            "spo2_percentage_average",
            "breathing_disturbance_index",
        ],
    }
    return [column for column in candidates_by_endpoint.get(endpoint, []) if column in frame.columns]


def render_profile(profile: dict[str, Any]) -> None:
    profile = profile.get("data", profile)

    st.subheader("Profil")
    columns = st.columns(4)
    values = [
        ("Alter", profile.get("age"), ""),
        ("Groesse", profile.get("height"), " m"),
        ("Gewicht", profile.get("weight"), " kg"),
        ("Geschlecht", profile.get("biological_sex"), ""),
    ]

    for column, (label, value, suffix) in zip(columns, values):
        column.metric(label, format_value(value, suffix))


def render_endpoint(label: str, endpoint: str, start_date: date, end_date: date) -> None:
    with st.spinner(f"{label}-Daten werden geladen..."):
        frame = get_daily_data(endpoint, start_date, end_date)

    st.subheader(label)
    if frame.empty:
        st.info("Keine Daten fuer diesen Zeitraum gefunden.")
        return

    metrics = metric_columns(frame, endpoint)
    if metrics:
        latest = frame.iloc[-1]
        columns = st.columns(min(len(metrics), 5))
        for index, metric in enumerate(metrics[:5]):
            columns[index].metric(metric.replace("_", " ").title(), format_value(latest.get(metric)))

    chartable = [column for column in metrics if pd.api.types.is_numeric_dtype(frame[column])]
    if "day" in frame.columns and chartable:
        chart_frame = frame.set_index("day")[chartable]
        st.line_chart(chart_frame)

    visible_columns = ["day"] + score_columns(frame)
    visible_columns = [column for column in visible_columns if column in frame.columns]
    if visible_columns:
        st.dataframe(frame[visible_columns], use_container_width=True, hide_index=True)

    with st.expander("Rohdaten ansehen"):
        st.dataframe(frame, use_container_width=True, hide_index=True)


def main() -> None:
    st.set_page_config(page_title="Oura Dashboard", page_icon="O", layout="wide")
    st.title("Oura Dashboard")

    if not ACCESS_TOKEN:
        st.error("Bitte OURA_ACCESS_TOKEN in der .env setzen und Streamlit neu starten.")
        st.stop()

    today = date.today()
    default_start = today - timedelta(days=14)

    with st.sidebar:
        st.header("Zeitraum")
        start = st.date_input("Start", value=default_start, max_value=today)
        end = st.date_input("Ende", value=today, max_value=today)
        selected = st.multiselect(
            "Daten",
            options=list(ENDPOINTS.keys()),
            default=["Sleep", "Readiness", "Activity"],
        )
        refresh = st.button("Neu laden")

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    try:
        profile = get_personal_info()
        render_profile(profile)

        for label in selected:
            render_endpoint(label, ENDPOINTS[label], start, end)

    except requests.HTTPError as exc:
        detail = exc.response.text if exc.response is not None else str(exc)
        st.error(f"Oura API Fehler: {detail}")
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
