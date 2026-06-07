# Google Health API v4: Endpunkte, Anmeldung und Token

Diese Notiz dokumentiert die aktuell verwendeten Google Health API v4 Endpunkte und den OAuth2-Flow im Projekt.

## Bestaetigter Ist-Stand (2026-06-07)

- Die neue App `google_health_fresh_app.py` nutzt nur Google OAuth2 + Google Health API v4.
- Es gibt dort keinen Firebase/Firestore-Zugriff.
- Direkter API-Zugriff mit gespeichertem Token wurde erfolgreich getestet.

Live-Test (mit vorhandenem Token) ergab:

- `GET /users/me/profile`: erfolgreich
- `GET /users/me/pairedDevices`: 1 Geraet
- `GET /users/me/dataTypes/exercise/dataPoints?pageSize=5`: 5 Eintraege
- `GET /users/me/dataTypes/sleep/dataPoints?pageSize=5`: 3 Eintraege
- `POST /users/me/dataTypes/steps/dataPoints:dailyRollUp`: 13 Tage, letzter Wert `6009` Schritte
- `POST /users/me/dataTypes/heart-rate/dataPoints:dailyRollUp`: 13 Tage, letzter Durchschnitt `64.07 bpm`

## Basis-URLs

- Auth (User Login): `https://accounts.google.com/o/oauth2/v2/auth`
- Token Exchange/Refresh: `https://oauth2.googleapis.com/token`
- Token Debug: `https://oauth2.googleapis.com/tokeninfo`
- Health API Base: `https://health.googleapis.com/v4`

## Verwendete Scopes (Read-Only)

- `https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly`
- `https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly`
- `https://www.googleapis.com/auth/googlehealth.location.readonly`
- `https://www.googleapis.com/auth/googlehealth.sleep.readonly`
- `https://www.googleapis.com/auth/googlehealth.profile.readonly`
- `https://www.googleapis.com/auth/googlehealth.settings.readonly`

Hinweis: `pairedDevices` benoetigt den Settings-Scope.

## Verwendete Endpunkte

### 1) Profil

- Methode: `GET`
- Path: `/users/me/profile`
- Vollstaendig: `https://health.googleapis.com/v4/users/me/profile`

### 2) Gekoppelte Geraete

- Methode: `GET`
- Path: `/users/me/pairedDevices`
- Vollstaendig: `https://health.googleapis.com/v4/users/me/pairedDevices`

### 3) Daily Rollup je Datentyp

- Methode: `POST`
- Path: `/users/me/dataTypes/{dataType}/dataPoints:dailyRollUp`
- Beispiel (Steps): `https://health.googleapis.com/v4/users/me/dataTypes/steps/dataPoints:dailyRollUp`

Uebliche Datentypen:

- `steps`
- `active-energy-burned`
- `distance`
- `heart-rate`
- `weight`

Beispiel-Body:

```json
{
  "range": {
    "start": { "date": { "year": 2026, "month": 6, "day": 1 } },
    "end": { "date": { "year": 2026, "month": 6, "day": 7 } }
  },
  "windowSizeDays": 1,
  "pageSize": 14,
  "dataSourceFamily": "users/me/dataSourceFamilies/all-sources"
}
```

Wichtig zu Limits:

- API prueft `windowSizeDays * pageSize` gegen datentyp-spezifische Limits.
- Wenn die Kombination ungueltig ist, kommt `INVALID_ROLLUP_QUERY_DURATION`.
- Fuer Heart-Rate ist das Maximum kleiner als bei anderen Typen.

### 4) Rohdaten-Listen (optional)

- Methode: `GET`
- Path: `/users/me/dataTypes/{dataType}/dataPoints?pageSize=...`
- Beispiele:
  - `/users/me/dataTypes/exercise/dataPoints?pageSize=5`
  - `/users/me/dataTypes/sleep/dataPoints?pageSize=5`

## So meldest du dich an und bekommst den Token

### Voraussetzungen in Google Cloud

1. OAuth Consent Screen konfigurieren.
2. OAuth Client erstellen (Typ `Web application` oder `Desktop app`).
3. Redirect URI freigeben (z. B. `http://localhost:8510`).
4. Google Health API im Projekt aktivieren.
5. Client-Secret-Datei als `client_secret_*.json` im Projekt ablegen.

### OAuth Ablauf (Authorization Code)

1. Browser aufrufen mit:
   - `client_id`
   - `redirect_uri`
   - `response_type=code`
   - `scope` (oben genannte Scopes)
   - `access_type=offline`
   - `prompt=consent`
   - `state` (CSRF-Schutz)
2. Nach Login kommt ein `code` auf der Redirect-URI zurueck.
3. `code` gegen Token tauschen via `POST https://oauth2.googleapis.com/token`.
4. Antwort enthaelt:
   - `access_token`
   - `refresh_token` (bei offline/consent)
   - `expires_in`
5. Access Token als `Authorization: Bearer <token>` an Health-API senden.
6. Bei Ablauf Access Token mit `refresh_token` erneuern.

## Beispiel: Token austauschen

```bash
curl -X POST https://oauth2.googleapis.com/token \
  -d client_id=YOUR_CLIENT_ID \
  -d client_secret=YOUR_CLIENT_SECRET \
  -d code=AUTH_CODE \
  -d grant_type=authorization_code \
  -d redirect_uri=http://localhost:8510
```

## Beispiel: Access Token refreshen

```bash
curl -X POST https://oauth2.googleapis.com/token \
  -d client_id=YOUR_CLIENT_ID \
  -d client_secret=YOUR_CLIENT_SECRET \
  -d refresh_token=YOUR_REFRESH_TOKEN \
  -d grant_type=refresh_token
```

## Token pruefen (Debug)

```bash
curl "https://oauth2.googleapis.com/tokeninfo?access_token=YOUR_ACCESS_TOKEN"
```

## Secret Handling im Repo

Diese Dateien gehoeren nicht in Git und sind in `.gitignore` eingetragen:

- `client_secret_*.json`
- `google_fit_tokens*.json`
- `.google_health_fresh_tokens.json`
- `google_health_tokens*.json`
- `*-firebase-adminsdk-*.json`
- `google-services.json`

## Im Projekt relevante Dateien

- `google_health_fresh_app.py`
- `fitbit_streamlit_app.py`
- `pages/1_Google_Fit_Rohdaten.py`
