from __future__ import annotations

from datetime import date, datetime, time as datetime_time, timedelta, timezone
from typing import Any

import streamlit as st

from fitbit_streamlit_app import (
    GOOGLE_FIT_ACCESS_TOKEN,
    GOOGLE_FIT_AGGREGATES,
    GOOGLE_FIT_REFRESH_TOKEN,
    build_google_auth_url,
    handle_google_oauth_callback,
    request_google_fit,
)


def millis_at_start_of_day(value: date) -> int:
    return int(datetime.combine(value, datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def millis_at_end_of_day(value: date) -> int:
    return int(datetime.combine(value + timedelta(days=1), datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def aggregate_payload(selected: list[str], start_date: date, end_date: date, timezone_id: str) -> dict[str, Any]:
    return {
        "startTimeMillis": millis_at_start_of_day(start_date),
        "endTimeMillis": millis_at_end_of_day(end_date),
        "aggregateBy": [{"dataTypeName": GOOGLE_FIT_AGGREGATES[label]["data_type"]} for label in selected],
        "bucketByTime": {"period": {"type": "day", "value": 1, "timeZoneId": timezone_id}},
    }


def main() -> None:
    st.set_page_config(page_title="Google Fit Rohdaten", page_icon="G", layout="wide")
    handle_google_oauth_callback()

    st.title("Google Fit Rohdaten")
    st.caption("Direkte Antworten der Google-Fit-REST-API. Keine Health-Connect- oder Firebase-Daten.")

    today = date.today()
    with st.sidebar:
        start = st.date_input("Start", value=today - timedelta(days=14), max_value=today)
        end = st.date_input("Ende", value=today, max_value=today)
        timezone_id = st.text_input("Timezone", value="Europe/Berlin")
        selected = st.multiselect(
            "Aggregate",
            options=list(GOOGLE_FIT_AGGREGATES.keys()),
            default=["Steps", "Calories", "Distance"],
        )
        show_sources = st.checkbox("Datenquellen", value=True)
        show_aggregate = st.checkbox("Tages-Aggregat", value=True)
        refresh = st.button("Neu laden")

        st.divider()
        auth_url = build_google_auth_url()
        if GOOGLE_FIT_ACCESS_TOKEN or GOOGLE_FIT_REFRESH_TOKEN:
            st.success("Google Fit verbunden")
            if auth_url:
                st.link_button("Google Fit neu verbinden", auth_url, use_container_width=True)
        elif auth_url:
            st.link_button("Mit Google Fit verbinden", auth_url, use_container_width=True)
        else:
            st.error("OAuth Client fehlt: client_secret_*.json nicht gefunden.")

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    if not GOOGLE_FIT_ACCESS_TOKEN and not GOOGLE_FIT_REFRESH_TOKEN:
        st.info("Bitte zuerst mit Google Fit verbinden.")
        st.stop()

    try:
        if show_sources:
            st.subheader("GET /users/me/dataSources")
            st.json(request_google_fit("GET", "/users/me/dataSources"), expanded=False)

        if show_aggregate and selected:
            payload = aggregate_payload(selected, start, end, timezone_id)
            st.subheader("POST /users/me/dataset:aggregate")
            st.markdown("Request")
            st.json(payload, expanded=False)
            st.markdown("Response")
            st.json(request_google_fit("POST", "/users/me/dataset:aggregate", json=payload), expanded=False)
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
