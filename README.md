# Simple Shield (Android)

A native Android app that blocks ad/tracker domains **device-wide** — every
app and browser, not just one — by filtering DNS lookups locally through a
local VPN. No root required. Companion to the "Simple Shield" Chrome
extension, sharing the same domain blocklist and branding.

## How it actually works (read this — it explains what it can't do too)

Android doesn't let a normal app inspect other apps' network traffic. The
one sanctioned way around that is `VpnService`: with the user's explicit
permission, an app can become a lightweight local VPN, and Android routes
traffic through it.

This app uses that ability for **one narrow purpose**: intercepting DNS
lookups (the "what's the IP address for doubleclick.net?" step every
connection starts with). It does this with a specific trick — it tells
Android its own virtual address is the DNS server to use, but only adds a
network *route* for that one address. Your actual web/video/app traffic
never enters the tunnel at all; only DNS queries do.

For every DNS query:
1. Read the domain being looked up.
2. If it's a known ad/tracker domain → reply "this domain doesn't exist"
   (NXDOMAIN). The requesting app never gets an IP to connect to.
3. Otherwise → forward to a real DNS server (Cloudflare, 1.1.1.1) and relay
   the real answer back. Normal browsing is unaffected.

**Honest limitations:**
- This only intercepts **IPv4** DNS traffic. Most Android networks still
  prefer/fall back to IPv4 DNS, but on an IPv6-only network this app
  wouldn't see those lookups. A known, documented gap, not a bug.
- Ads served from the SAME domain as legitimate content (rather than a
  dedicated ad-serving domain) won't be caught by DNS-level blocking — this
  is a fundamental limit of the DNS-filtering approach itself, not specific
  to this implementation. This is the same limitation every DNS-based
  blocker (AdGuard, NextDNS, DNS66, etc.) has.
- Only one VPN app can be active at a time on Android — using this means
  you can't simultaneously use a different VPN (for privacy/geo-unblocking/
  work) unless it explicitly supports chaining.

## How I verified the trickiest part without an Android device

The riskiest code here is low-level: parsing raw DNS messages and building
correctly-checksummed IPv4/UDP packets by hand. I don't have an Android
build toolchain in the environment I built this in, so I verified this
logic in three steps before trusting it:
1. Implemented and tested the exact algorithm in Python (fast to iterate).
2. Translated it to Kotlin and **actually compiled and ran it** standalone
   (outside Android — this logic has no Android dependencies) against the
   same test cases.
3. Cross-checked that the Python and Kotlin implementations produce
   **byte-for-byte identical output** for the same input.

The corresponding tests live in `app/src/test/` — run them yourself anytime
with `./gradlew test` in Android Studio.

The parts I *couldn't* verify this way (the `VpnService`/notification/UI
code, which needs the real Android SDK) are written to closely follow
documented, standard Android APIs, but you should still test on a real
device or emulator before publishing — see below.

## Building this

1. Install Android Studio (free, from developer.android.com).
2. Open this folder as a project (File → Open).
3. Let Gradle sync — it will likely prompt to update the Android
   Gradle Plugin/Kotlin versions in `build.gradle`; accepting is normal.
4. Run `./gradlew test` (or the "Run Tests" button) to re-verify the logic
   tests pass in the real toolchain.
5. Run the app on an emulator or real device (Run ▶ button). Tap the
   toggle, grant the VPN permission prompt, and try browsing — check
   Logcat (filter: `SimpleShieldVpn`) to see blocked domains logged live.

## Publishing to the Play Store — please read before you submit

- **A privacy policy is mandatory.** Any app requesting sensitive
  permissions (VPN service counts) must link a privacy policy in its Play
  Console listing. This app collects nothing and sends nothing anywhere —
  say so plainly in that policy.
- **VPN-category apps get extra review scrutiny.** Google reviews apps
  using `VpnService` more carefully than average, and may ask clarifying
  questions about what the VPN does. Being able to point to this app's
  actual behavior (DNS filtering only, open about it, no data collection)
  is exactly what that review is checking for.
- You'll need a **Google Play Developer account** (one-time $25 fee) —
  something I can't create on your behalf.
- Fill in `app/build.gradle`'s `applicationId` with your own reverse-domain
  identifier before publishing if `com.simpleshield.adblocker` isn't yours
  to use.

## Files

```
app/src/main/java/com/simpleshield/adblocker/
  MainActivity.kt          The one screen: on/off toggle + blocked count
  AdBlockVpnService.kt      The VPN service — the actual ad-blocking logic
  DnsMessageLogic.kt        Pure DNS message parsing/building (tested)
  IpPacketLogic.kt          Pure IPv4/UDP packet construction (tested)
  BlockedDomainList.kt      The domain blocklist + matching logic (tested)
app/src/test/...            JUnit tests mirroring the standalone verification
app/src/main/res/           Layout, strings, colors, icons
generate_launcher_icons.py  Regenerates the app icons if you want to restyle
```
