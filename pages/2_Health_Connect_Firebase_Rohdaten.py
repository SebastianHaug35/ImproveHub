from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Any

import streamlit as st

from fitbit_streamlit_app import (
    FIREBASE_HEALTH_COLLECTION,
    FIREBASE_HEALTH_USER_ID,
    get_firestore_client,
)


def json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [json_safe(item) for item in value]
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def doc_payload(doc: Any) -> dict[str, Any]:
    return {
        "_id": doc.id,
        "_path": doc.reference.path,
        "data": json_safe(doc.to_dict() or {}),
    }


def main() -> None:
    st.set_page_config(page_title="Health Connect Rohdaten", page_icon="H", layout="wide")

    st.title("Health Connect / Firebase Rohdaten")
    st.caption("Direkte Firestore-Dokumente, die aus dem Android-Health-Connect-Exporter hochgeladen wurden.")

    today = date.today()
    with st.sidebar:
        start = st.date_input("Start", value=today - timedelta(days=14), max_value=today)
        end = st.date_input("Ende", value=today, max_value=today)
        user_id = st.text_input("Firestore User ID", value=FIREBASE_HEALTH_USER_ID)
        collection = st.text_input("Collection", value=FIREBASE_HEALTH_COLLECTION)
        import_limit = st.number_input("Importe anzeigen", min_value=1, max_value=50, value=5, step=1)
        show_user_doc = st.checkbox("Root-Dokument", value=True)
        show_daily = st.checkbox("Daily-Dokumente", value=True)
        show_imports = st.checkbox("Import-Dokumente", value=True)
        refresh = st.button("Neu laden")

    if refresh:
        st.cache_data.clear()

    if start > end:
        st.error("Das Startdatum muss vor dem Enddatum liegen.")
        st.stop()

    db = get_firestore_client()
    if db is None:
        st.error("Firebase Admin SDK ist nicht verfuegbar oder die Service-Account-Datei fehlt.")
        st.stop()

    user_ref = db.collection(collection).document(user_id)

    try:
        if show_user_doc:
            st.subheader(f"{collection}/{user_id}")
            user_doc = user_ref.get()
            if user_doc.exists:
                st.json(doc_payload(user_doc), expanded=False)
            else:
                st.info("Root-Dokument existiert nicht.")

        if show_daily:
            st.subheader(f"{collection}/{user_id}/healthConnectDaily")
            docs = (
                user_ref.collection("healthConnectDaily")
                .where("day", ">=", start.isoformat())
                .where("day", "<=", end.isoformat())
                .stream()
            )
            daily_payloads = [doc_payload(doc) for doc in docs]
            if daily_payloads:
                st.json(daily_payloads, expanded=False)
            else:
                st.info("Keine Daily-Dokumente im Zeitraum gefunden.")

        if show_imports:
            st.subheader(f"{collection}/{user_id}/healthConnectImports")
            docs = user_ref.collection("healthConnectImports").limit(int(import_limit)).stream()
            import_payloads = [doc_payload(doc) for doc in docs]
            if import_payloads:
                st.json(import_payloads, expanded=False)
            else:
                st.info("Keine Import-Dokumente gefunden.")
    except Exception as exc:
        st.error(str(exc))


if __name__ == "__main__":
    main()
