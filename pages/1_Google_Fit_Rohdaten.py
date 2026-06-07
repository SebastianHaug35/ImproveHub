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
    request_google_health,
)


def millis_at_start_of_day(value: date) -> int:
    return int(datetime.combine(value, datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def millis_at_end_of_day(value: date) -> int:
    return int(datetime.combine(value + timedelta(days=1), datetime_time.min, tzinfo=timezone.utc).timestamp() * 1000)


def aggregate_payload(selected: list[str], start_date: date, end_date: date, timezone_id: str) -> dict[str, Any]:
    return {
        "range": {
            "start": {"date": {"year": start_date.year, "month": start_date.month, "day": start_date.day}},
            "end": {"date": {"year": (end_date + timedelta(days=1)).year, "month": (end_date + timedelta(days=1)).month, "day": (end_date + timedelta(days=1)).day}},
        },
        "windowSizeDays": 1,
        "pageSize": 100,
        "dataSourceFamily": "users/me/dataSourceFamilies/all-sources",
    }


def main() -> None:
    st.set_page_config(page_title="Google Health API Rohdaten", page_icon="G", layout="wide")
    handle_google_oauth_callback()

    st.title("Google Health API Rohdaten")
    st.caption("Direkte Antworten der Google Health API. Keine Health-Connect- oder Firebase-Daten.")

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
            st.success("Google Health API verbunden")
            if auth_url:
                st.link_button("Google Health API neu verbinden", auth_url, use_container_width=True)
        elif auth_url:
            st.link_button("Mit Google Health API verbinden", auth_url, use_container_width=True)
        else:
            st.error("OAuth Client fehlt: client_secret_*.json nicht gefunden.")

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    if not GOOGLE_FIT_ACCESS_TOKEN and not GOOGLE_FIT_REFRESH_TOKEN:
        st.info("Bitte zuerst mit Google Health API verbinden.")
        st.stop()

    try:
        if show_sources:
            st.subheader("GET /users/me/pairedDevices")
            st.json(request_google_health("GET", "/users/me/pairedDevices"), expanded=False)

        if show_aggregate and selected:
            st.subheader("POST /users/me/dataTypes/*/dataPoints:dailyRollUp")
            for label in selected:
                payload = aggregate_payload([label], start, end, timezone_id)
                data_type = GOOGLE_FIT_AGGREGATES[label]["data_type"]
                st.markdown(f"**{label}** ({data_type})")
                st.markdown("Request")
                st.json(payload, expanded=False)
                st.markdown("Response")
                st.json(
                    request_google_health(
                        "POST",
                        f"/users/me/dataTypes/{data_type}/dataPoints:dailyRollUp",
                        json=payload,
                    ),
                    expanded=False,
                )
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
