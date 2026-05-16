from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import firebase_admin
from firebase_admin import credentials, firestore


DEFAULT_SERVICE_ACCOUNT = "time-tracking-61b9b-firebase-adminsdk-fbsvc-5d690a80c7.json"
DEFAULT_USER_ID = "default"
DEFAULT_COLLECTION = "healthConnectUsers"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upload a Health Connect JSON export to Firestore without deleting existing data."
    )
    parser.add_argument("export_file", help="Path to the exported Health Connect JSON file.")
    parser.add_argument(
        "--service-account",
        default=DEFAULT_SERVICE_ACCOUNT,
        help=f"Path to the Firebase service account JSON. Default: {DEFAULT_SERVICE_ACCOUNT}",
    )
    parser.add_argument(
        "--user-id",
        default=DEFAULT_USER_ID,
        help=f"Logical user id inside Firestore. Default: {DEFAULT_USER_ID}",
    )
    parser.add_argument(
        "--collection",
        default=DEFAULT_COLLECTION,
        help=f"Top-level Firestore collection for health exports. Default: {DEFAULT_COLLECTION}",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def init_firestore(service_account_path: Path) -> firestore.Client:
    app_name = "health-connect-uploader"
    try:
        app = firebase_admin.get_app(app_name)
    except ValueError:
        app = firebase_admin.initialize_app(credentials.Certificate(str(service_account_path)), name=app_name)
    return firestore.client(app=app)


def upload_export(db: firestore.Client, export_data: dict[str, Any], user_id: str, collection_name: str) -> None:
    exported_at = str(export_data.get("exportedAt", "unknown"))
    user_ref = db.collection(collection_name).document(user_id)

    user_ref.set(
        {
            "healthConnect": {
                "lastExportAt": exported_at,
                "lastZoneId": export_data.get("zoneId"),
                "lastRange": {
                    "start": export_data.get("start"),
                    "end": export_data.get("end"),
                },
                "sourceApps": export_data.get("sources", []),
            }
        },
        merge=True,
    )

    import_ref = user_ref.collection("healthConnectImports").document(exported_at.replace(":", "-"))
    import_ref.set(
        {
            "exportedAt": exported_at,
            "zoneId": export_data.get("zoneId"),
            "start": export_data.get("start"),
            "end": export_data.get("end"),
            "sources": export_data.get("sources", []),
            "daysCount": len(export_data.get("days", [])),
            "raw": export_data,
        },
        merge=True,
    )

    batch = db.batch()
    days_collection = user_ref.collection("healthConnectDaily")
    for day in export_data.get("days", []):
        day_id = str(day["day"])
        doc_ref = days_collection.document(day_id)
        batch.set(
            doc_ref,
            {
                "day": day["day"],
                "provider": "health_connect",
                "exportedAt": exported_at,
                "zoneId": export_data.get("zoneId"),
                "metrics": {
                    "steps": day.get("steps"),
                    "distanceMeters": day.get("distanceMeters"),
                    "distanceSamples": day.get("distanceSamples"),
                    "activeCaloriesKcal": day.get("activeCaloriesKcal"),
                    "activeCaloriesSamples": day.get("activeCaloriesSamples"),
                    "totalCaloriesKcal": day.get("totalCaloriesKcal"),
                    "totalCaloriesSamples": day.get("totalCaloriesSamples"),
                    "sleepMinutes": day.get("sleepMinutes"),
                    "sleepSessionCount": day.get("sleepSessionCount"),
                    "latestSleepStart": day.get("latestSleepStart"),
                    "latestSleepEnd": day.get("latestSleepEnd"),
                    "sleepAwakeMinutes": day.get("sleepAwakeMinutes"),
                    "sleepLightMinutes": day.get("sleepLightMinutes"),
                    "sleepDeepMinutes": day.get("sleepDeepMinutes"),
                    "sleepRemMinutes": day.get("sleepRemMinutes"),
                    "sleepUnknownMinutes": day.get("sleepUnknownMinutes"),
                    "heartRateAvgBpm": day.get("heartRateAvgBpm"),
                    "heartRateRecordCount": day.get("heartRateRecordCount"),
                    "restingHeartRateAvgBpm": day.get("restingHeartRateAvgBpm"),
                    "restingHeartRateRecordCount": day.get("restingHeartRateRecordCount"),
                    "hrvRmssdAvgMillis": day.get("hrvRmssdAvgMillis"),
                    "hrvRecordCount": day.get("hrvRecordCount"),
                    "weightLatestKg": day.get("weightLatestKg"),
                    "weightRecordCount": day.get("weightRecordCount"),
                    "sourceApps": day.get("sourceApps", []),
                    "sleepSessions": day.get("sleepSessions", []),
                },
                "raw": day,
            },
            merge=True,
        )
    batch.commit()


def main() -> None:
    args = parse_args()
    export_path = Path(args.export_file)
    service_account_path = Path(args.service_account)

    if not export_path.exists():
        raise SystemExit(f"Export file not found: {export_path}")
    if not service_account_path.exists():
        raise SystemExit(f"Service account file not found: {service_account_path}")

    export_data = load_json(export_path)
    db = init_firestore(service_account_path)
    upload_export(db, export_data, args.user_id, args.collection)

    print("Upload complete.")
    print(f"User document: {args.collection}/{args.user_id}")
    print(f"Daily entries written: {len(export_data.get('days', []))}")
    print("Existing data in other collections was not deleted.")


if __name__ == "__main__":
    main()
