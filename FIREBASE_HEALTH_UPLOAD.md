# Health Connect -> Firebase

The Android app should **not** contain the Firebase admin service account. That key has full server-side privileges.

This repo now includes a local uploader script:

`upload_health_connect_export.py`

It uploads a Health Connect JSON export to Firestore using merge/upsert behavior, so it does **not** wipe other existing data.

## Firestore structure

- `healthConnectUsers/{userId}`
  - `healthConnect.lastExportAt`
  - `healthConnect.lastZoneId`
  - `healthConnect.lastRange`
  - `healthConnect.sourceApps`
- `healthConnectUsers/{userId}/healthConnectImports/{exportedAt}`
  - full raw import payload
- `healthConnectUsers/{userId}/healthConnectDaily/{yyyy-mm-dd}`
  - merged daily metrics

## Install dependency

```powershell
pip install firebase-admin
```

Or:

```powershell
pip install -r requirements.txt
```

## Run upload

```powershell
python upload_health_connect_export.py path\to\health-export.json
```

Optional:

```powershell
python upload_health_connect_export.py path\to\health-export.json --user-id sebastian
```

Optional custom collection:

```powershell
python upload_health_connect_export.py path\to\health-export.json --collection healthConnectUsers
```

## Important behavior

- The script uses Firestore `merge=True`.
- It does not delete other collections or documents.
- If you upload the same day again, the matching `healthConnectDaily/{day}` document is updated, not duplicated.
