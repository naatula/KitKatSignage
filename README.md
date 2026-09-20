# KitKat Signage

A minimal Android digital-signage app: it shows one web page, full screen,
forever. The address is asked for once, on first launch, and then kept on the
device — there is nothing else to configure and no way out of the signage view.

It is built for **legacy hardware**: `minSdk` is 19, so it still installs on
Android 4.4 (KitKat) signage panels and set-top boxes, and it depends on no
support/AndroidX libraries.

## What it does

- Loads the configured URL in a full-screen WebView (immersive mode, no
  navigation bar, Back button disabled, landscape).
- Stays on the configured host only: navigation to any other host, and any
  cleartext `http://` URL, is refused.
- Shows a dark "Connecting…" page and retries every 15 seconds when the site
  or the network is down, so the panel never sits on a browser error page.
- Fails closed on TLS problems, showing a "Secure connection failed" page
  instead of silently proceeding.

## Setting it up

1. Build and install the APK:

   ```
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   For real deployments build a signed release instead — see below.

2. Launch the app. On first run it asks for the address to display. Enter an
   `https://` URL — a bare `example.com/signage` is accepted and gets the
   `https://` prefix; `http://` is rejected.

3. Tap **Start**. The URL is saved and the signage view opens. Every later
   launch goes straight to that URL.

To point the device somewhere else, clear the app's data
(`adb shell pm clear fi.naatula.kitkatsignage`, or Settings → Apps → KitKat
Signage → Clear data) or reinstall the app. The setup screen then comes back.
The saved URL is excluded from Android backups (`allowBackup="false"`), so it
cannot leak onto another device via a restore — this matters if your URL
carries an access token.

## Release builds

`assembleRelease` signs the APK with a key described by `keystore.properties`
in the project root, which is gitignored and never shared. Copy the template
and point it at your own key:

```
cp keystore.properties.example keystore.properties
keytool -genkeypair -v -keystore release.keystore.jks \
    -alias kitkatsignage -keyalg RSA -keysize 2048 -validity 10000
./gradlew assembleRelease
```

Without `keystore.properties` the signing config stays empty, so a fresh
clone still builds — it just produces an unsigned release APK. Keep the
keystore itself safe: Android refuses to update an installed app with one
signed by a different key, so losing it means uninstalling every display
before it can be updated again.

## Legacy devices and Let's Encrypt

KitKat-era devices ship a CA trust store that was frozen years ago, so a site
with a current Let's Encrypt certificate fails to load on them: the device has
never heard of the issuing CA. That, not an insecure site, is what breaks
signage on old panels.

Instead of accepting every certificate (the usual, terrible workaround), this
app pins the Let's Encrypt intermediates in
`app/src/main/res/raw/letsencrypt_ca.pem`. When the WebView reports an unknown
CA, and only then, the presented certificate is accepted if — and only if — it

- was signed by one of the bundled intermediates,
- is currently valid, and
- actually matches the configured host (SAN, or CN for SAN-less certificates).

Any other TLS error (expiry, hostname mismatch, anything unrecognised) stays
fatal, and if the PEM file cannot be read, every TLS error is fatal.

Because only the leaf certificate is available at this point, the bundled PEM
must contain the **direct issuer** of your server certificate, i.e. the
intermediate(s) — not the root. Let's Encrypt rotates intermediates, so when
they change, replace the file with the current ones from
<https://letsencrypt.org/certificates/> (several PEM blocks may simply be
concatenated in the file) and reinstall.

If your site uses a certificate the device already trusts, the pinning code
never runs and the app just works.

## Notes

- `targetSdk` is deliberately kept at 28: this is a sideloaded appliance app,
  and opting in to newer platform restrictions buys it nothing.
- JavaScript, DOM storage and autoplay without a user gesture are enabled;
  file and content access are disabled.
