# Shield - Ad Blocker

A private, device-wide DNS ad blocker for Android. Shield filters known
advertising domains across apps and browsers without root access.

## Features

- Device-wide DNS filtering
- IPv4 and IPv6 support
- 12,000+ offline rules with automatic updates
- Custom blocklist and allowlist
- Per-app compatibility exclusions
- Local block statistics
- 24-hour protection sessions
- No advertising or analytics SDKs

## How it works

Shield uses Android's local `VpnService` to receive DNS requests. Only DNS
packets enter the VPN; messages, passwords, web content, videos, and other app
traffic remain on the normal connection.

Known advertising domains receive a blocked response. Allowed domains resolve
normally. Android displays its required VPN indicator while protection is
active.

DNS filtering cannot block ads served from the same domain as legitimate
content, embedded video sponsorships, or apps that bypass system DNS.

## Build

Requirements: Android Studio, JDK 17, and Android SDK 37.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For a release bundle:

```bash
./gradlew lintRelease bundleRelease
```

Use a private upload key for Play Store releases. Never commit signing keys or
passwords.

## Privacy

Shield has no remote VPN server and does not collect or sell personal data.
See the [published privacy policy](https://laudarisd.github.io/simple-shield-android-app/)
for details.

## License

Copyright © 2026 Sudip Laudari. All rights reserved.

This repository is source-available, not open source. The original code,
branding, design, and artwork may not be copied, modified, or redistributed
without written permission. See [LICENSE](LICENSE).

Third-party blocklists retain their own licenses. See
[THIRD_PARTY_NOTICES.txt](app/src/main/assets/THIRD_PARTY_NOTICES.txt).
