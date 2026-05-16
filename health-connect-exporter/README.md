# ImproveHUB Health Connect Exporter

Small Android app that reads Health Connect data from the connected Android device and shares a JSON export.

## What It Reads

- Steps
- Distance
- Active calories burned
- Total calories burned
- Heart rate
- Resting heart rate
- HRV RMSSD
- Sleep sessions
- Weight

The app reads directly from Android Health Connect. This is the path you need when Fitbit writes data into Health Connect but the old Google Fit REST API does not expose the same data.

## Open In Android Studio

1. Install Android Studio.
2. Open the folder `health-connect-exporter`.
3. Let Android Studio sync Gradle.
4. Connect your Android phone with USB debugging enabled.
5. Run the `app` configuration.

## On The Phone

1. Make sure Health Connect is available.
   - Android 14+: Settings -> Security and privacy -> Health Connect.
   - Android 13 or lower: install Health Connect from the Play Store.
2. Make sure Fitbit is connected to Health Connect and has written data.
3. Open this app.
4. Tap `Request Health Connect access`.
5. Grant the requested read permissions.
6. Tap `Read last 14 days`.
7. Tap `Share JSON export` to send the JSON to your laptop, cloud storage, email, or another app.

## Notes

This app is intentionally local-first. It does not upload data automatically yet. Once the JSON shape looks good, the next step can be a Firebase upload or a local HTTP sync endpoint for the Streamlit dashboard.
