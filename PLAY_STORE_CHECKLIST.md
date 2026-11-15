# Google Play release checklist

## Before creating the release

- Privacy policy published at
  `https://laudarisd.github.io/simple-shield-android-app/`.
- Verify the permanent application ID `io.github.laudarisd.shield`; it cannot
  be changed after the first Play release.
- Set a new `versionCode` for every upload and a user-facing `versionName`.
- Create a private upload keystore and configure release signing outside Git.
- Run `./gradlew testDebugUnitTest lintRelease bundleRelease`.
- Test on physical devices across Android 8, 13, and the newest supported
  Android version, using Wi-Fi, mobile data, IPv4, IPv6, sleep, and handoff.

## Play Console declarations

- Complete the VPN service declaration accurately: local on-device DNS ad and
  tracker filtering, no sale or sharing of VPN traffic, and clear disclosure.
- Complete Data safety from the shipped build, not from marketing intent.
- Provide the privacy-policy URL and support contact.
- Declare foreground-service use and verify the special-use explanation.
- Supply app-access instructions if review requires a particular test flow.

## Required manual behavior checks

- Starting requires the Android VPN consent screen on first use.
- Only the two synthetic DNS routes enter the TUN interface.
- Allowed domains resolve and blocked domains return NXDOMAIN.
- Banking, messaging, streaming, browser, and Play Store app data still work.
- Stop disconnects immediately; automatic stop occurs after 24 hours.
- Switching Wi-Fi/mobile and losing/recovering connectivity does not crash.
- Excluded apps bypass Shield after reconnecting protection.
