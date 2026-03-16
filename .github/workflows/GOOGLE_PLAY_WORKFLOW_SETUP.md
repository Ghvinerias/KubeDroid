# Google Play GitHub Workflow Setup

`docs/ci/google-play-publish.yml` is a ready workflow template.

Due to repo guardrails, move it manually to:

`/.github/workflows/google-play-publish.yml`

## Required GitHub Secrets

Create these repository secrets:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`: base64 content of your upload keystore (`.jks`)
- `KUBEDROID_STORE_PASSWORD`
- `KUBEDROID_KEY_ALIAS`
- `KUBEDROID_KEY_PASSWORD`
- `PLAY_SERVICE_ACCOUNT_JSON`: full JSON key content for Google Play Android Publisher service account

## One-time setup notes

1. In Google Play Console, grant your service account access to the app.
2. Keep first releases on `internal` track until validated.
3. Trigger workflow manually from Actions tab and choose track/status.
