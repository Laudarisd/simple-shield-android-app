# Shield - Ad Blocker for Android

Shield is a native Android DNS blocker designed to reduce advertising and
tracking requests across apps and browsers without root access. It uses a
local, DNS-only `VpnService`; ordinary application traffic never enters the
VPN interface.

> **Project status:** Active development. The core blocker builds successfully,
> passes Android lint, and has automated tests for DNS, packet, and rule logic.
> Physical-device testing and Play Console setup are still required before a
> public production release.

## Highlights

- Device-wide DNS filtering for apps and browsers
- IPv4 and direct IPv6 UDP DNS support
- More than 12,000 bundled offline domain rules
- Automatic blocklist refresh with safe offline fallback
- Custom block and allow rules
- Per-app compatibility exclusions
- Local blocked-request counters and recent activity
- 24-hour protection session after each user activation
- No root requirement
- No advertising or analytics SDKs
- No remote Shield VPN server

## How it works

Android does not offer ordinary apps a system-wide ad-blocking permission.
Shield therefore uses Android's supported `VpnService` API to create a local
DNS interface after the user approves the system VPN prompt.

Shield installs routes only for its two synthetic DNS addresses. For each DNS
request:

1. Shield validates and reads the requested domain.
2. A known advertising domain receives an `NXDOMAIN` response.
3. An allowed domain is forwarded to an upstream DNS resolver.
4. The DNS response is returned to the requesting app.

Web pages, messages, passwords, downloads, audio, video, and other application
payloads continue over the device's normal connection. Shield does not read or
route that content.

Android displays its VPN key indicator while protection is active. This is a
mandatory system security feature and cannot be hidden by the app.

## Privacy

Shield is designed around a narrow traffic boundary:

- Only DNS packets addressed to Shield enter the local VPN.
- Block statistics and recent domains are stored only in private app storage.
- Local statistics can be disabled in Settings.
- App data is excluded from Android backup and device transfer.
- No advertising or analytics SDK is included.
- Shield does not operate a remote VPN or data-collection server.

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the publication template. Its
publisher name, contact address, and effective date must be completed before
release.

## Limitations

DNS filtering cannot safely remove every advertisement:

- Ads served from the same domain as legitimate content cannot be separated at
  DNS level without also breaking the content.
- Apps using private DNS-over-HTTPS, DNS-over-TLS, hard-coded IP addresses, or
  cached connections may bypass local DNS filtering.
- Embedded sponsor segments inside videos are part of the video stream and
  cannot be removed by DNS filtering.
- Only one Android VPN can be active for a user at a time.
- IPv6 extension-header packets outside the supported direct UDP path are
  rejected rather than inspected.

The rules intentionally favor app compatibility over aggressive blocking.

## Requirements

- Android Studio with JDK 17
- Android SDK 37
- Android 8.0/API 26 or newer device or emulator

## Build and verify

Clone the repository and open its root directory in Android Studio, or use the
Gradle wrapper:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Verify the optimized release variant with:

```bash
./gradlew lintRelease bundleRelease
```

Production publishing requires a private upload key. Never commit keystores,
key passwords, service-account credentials, or Play Console secrets.

## Testing on a device

1. Install and open Shield.
2. Tap **Start protection**.
3. Accept Android's VPN permission dialog.
4. Confirm that ordinary browsing and installed apps still work.
5. Confirm that known advertising domains appear under recently blocked.
6. Test Stop, Wi-Fi/mobile handoff, reconnect, IPv4, IPv6, and device sleep.

The VPN lifecycle and network handoff behavior must be validated on physical
devices before publishing. See [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md)
for the full release checklist.

## Project structure

```text
app/src/main/
├── AndroidManifest.xml              Permissions and Android components
├── assets/
│   ├── production_domains.txt       Bundled offline domain rules
│   └── THIRD_PARTY_NOTICES.txt      Rule-list licenses and attribution
├── java/com/simpleshield/adblocker/
│   ├── AdBlockVpnService.kt         VPN lifecycle and DNS forwarding
│   ├── DnsMessageLogic.kt           Validated DNS parsing and responses
│   ├── IpPacketLogic.kt             IPv4/IPv6 UDP parsing and construction
│   ├── DomainBlocklist.kt           Bundled and refreshed rule management
│   ├── DomainRulesStore.kt          User block and allow rules
│   ├── MainActivity.kt              Protection controls and status
│   ├── FiltersActivity.kt           Filter and domain-rule UI
│   └── SettingsActivity.kt          Privacy, theme, and app exclusions
└── res/                             Layouts, themes, strings, and artwork

app/src/test/                        Unit tests
PRIVACY_POLICY.md                    Publication privacy-policy template
PLAY_STORE_CHECKLIST.md              Release and device-test checklist
LICENSE                              First-party proprietary terms
```

## Blocklist attribution

Bundled rules combine and reformat data from AdAway, StevenBlack, hostsVN, and
Tiuxo. Each source remains subject to its own license. Full acknowledgements
are included in
[`app/src/main/assets/THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt).

## License

This repository is **source-available, not open source**. The original Shield
code, branding, design, and artwork are proprietary and all rights are
reserved. Viewing this public repository does not grant permission to copy,
modify, redistribute, sell, or publish the first-party materials.

See [LICENSE](LICENSE) for the complete terms. Third-party components and
blocklists remain governed by their respective licenses.

Copyright © 2026 Sudip Laudari. All rights reserved.
