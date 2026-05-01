# Captchala Android Demo

A Jetpack Compose demo app that exercises the Captchala Android SDK
end-to-end. Mirrors how a third-party integrator pulls in the pre-built
`.aar`.

## Links

- Website: <https://captcha.la>
- Dashboard: <https://dash.captcha.la>
- Android SDK docs: <https://captcha.la/docs/sdk/android>
- All SDK docs: <https://captcha.la/docs>
- Support: <support@captcha.la>

## Setup

1. Install **Android Studio Hedgehog (2023.1.1)** or newer.
2. Configure your Android SDK path:
   ```bash
   cp local.properties.example local.properties
   # then edit local.properties to point at your SDK install
   ```
3. Sign in to <https://dash.captcha.la> and download the latest Android
   release. Drop the `.aar` into `app/libs/`:
   ```
   app/libs/captchala.aar
   ```

## Run

```bash
./gradlew installDebug
```

Or open the project in Android Studio and hit **Run**.

## App key

The demo hard-codes a public demo `appKey` in `MainActivity.kt`. Replace
with your own from <https://dash.captcha.la> for real testing.

## Updating the SDK

Replace `app/libs/captchala.aar` with the new version from the dashboard
and rebuild.

## License

MIT — see [LICENSE](./LICENSE).
