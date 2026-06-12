# Android Manual Setup Guide

This is the Android portion of the manual setup flow for the PiStream / Pi
House audio companion app.

Use this after the Raspberry Pi audio services and local API have been set up.
The phone and Raspberry Pi must be on the same trusted home network.

## 1. Install the Android App

1. Install the approved Android companion app build on the phone.
2. Keep the phone on the home Wi-Fi network used by the Raspberry Pi.
3. Open the app.
4. If Android asks for network access, allow it.
5. Do not pair Bluetooth speakers from Android for this setup. Bluetooth speaker
   pairing and reconnects are managed by the Raspberry Pi.

Expected Phase 3 Android permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

The app should not ask for Android Bluetooth permissions, notification
permission, location permission, or multicast permission during this manual
Phase 3 setup.

## 2. Confirm Same-Network Access

1. Confirm the phone is connected to the home Wi-Fi, not cellular-only data.
2. Confirm the Raspberry Pi is connected to the same LAN.
3. If the Wi-Fi has guest isolation or client isolation enabled, move the phone
   and Pi to the normal trusted network.
4. If the phone is on a VPN, disable the VPN temporarily and try again.
5. If the router shows connected clients, confirm the Pi appears online.

The app connects to the Pi local API at:

```text
http://<pi-hostname-or-ip>:8765/api/v1
```

## 3. Find or Enter the Pi

The app should try these options in order:

1. Saved Pi host from a previous setup.
2. Default hostname: `audiopi.local`.
3. Manual hostname or IP entry.

If automatic discovery fails:

1. Tap manual entry.
2. Enter only the hostname or IP address, such as `audiopi.local`,
   `pihouse.local`, or `192.168.1.25`.
3. Do not include `http://`, port `8765`, or `/api/v1`; the app adds those.
4. Retry the connection.

If the Pi can be reached, the app calls `GET /identity` first. The app must not
send status, diagnostics, or repair commands until the identity check passes.

Android app reachability check:

1. The app should treat a successful `GET /identity` response as the primary Pi
   discovery ping.
2. After identity passes, the app may call `GET /health` for a quick readiness
   check.
3. The app should not require ICMP ping support from the phone or router.
4. If a human needs to verify the Pi outside the app, ping the Pi hostname or IP
   from another LAN device, then confirm port `8765` is reachable.

## 4. Pair and Trust the Pi

The app trusts the Pi by its local API identity, not by hostname or IP address.
Hostnames and IP addresses can change.

The finalized identity contract uses this saved identity tuple:

```text
apiName
contractVersion
deviceId
controllerInstanceId
```

Expected compatibility values:

```text
apiName: pihouse-audio-api
contractVersion: 2026-06-phase3
apiVersion: 1.x.y, minimum 1.0.0
```

For first-time pairing:

1. The app calls `GET /identity`.
2. The app verifies the API name, contract version, and API version.
3. The app requires non-empty `deviceId` and `controllerInstanceId`.
4. The user provides valid authorization for the bearer-protected API.
5. The app saves the host and identity tuple.
6. The app loads `GET /status`.

For an already paired phone:

1. The app calls `GET /identity` on the saved or entered host.
2. The app compares the returned `apiName`, `contractVersion`, `deviceId`, and
   `controllerInstanceId` with the saved values.
3. If those values match, hostname or IP address changes are allowed.
4. If `deviceId` or `controllerInstanceId` differs, the app must show a wrong
   device state and block controls.

Authorization expectations:

- `GET /identity` is public on the trusted LAN.
- `GET /health` is configurable and may be public or bearer-protected.
- `GET /status`, diagnostics, and all `/operations/*` calls require
  `Authorization: Bearer <token>`.
- Phase 3 has no login session, cookie, refresh token, or token renewal flow.

## 5. What Success Looks Like

After setup succeeds, the app should show:

- Connected Pi identity and connection badge.
- Overall state `found_healthy` or `found_unhealthy`.
- Indoor, Outdoor, and Whole House route status.
- Indoor and outdoor speaker status.
- Spotify endpoint readiness.
- Watchdog status.
- Refresh status action.
- Reconnect or run-watchdog actions only when the Pi reports a degraded route.
- Advanced diagnostics for logs and service restart if authorized.

Healthy setup means:

- The app verified the expected Pi identity.
- Authorization succeeded for `GET /status`.
- The dashboard loads current Pi status.
- Required audio components are healthy.

Degraded setup still means the app found the correct Pi, but one or more audio
components need attention.

## 6. What Failure Looks Like

| App state | Meaning | User action |
| --- | --- | --- |
| `not_found` | No saved, default, or manual Pi target can be reached. | Check Wi-Fi, VPN, router isolation, Pi power, and manual IP. |
| `api_unavailable` | The host exists, but the API is down, incompatible, malformed, or blocked. | Check the Pi API service and port `8765`. |
| `wrong_device` | A host responded, but it is not the paired Pi audio controller. | Check the hostname/IP and connect to the correct Pi. |
| `unauthorized` | The Pi was found, but the bearer token is missing or invalid. | Re-enter or rescan the token, then retry status. |
| `found_unhealthy` | The right Pi is reachable, but an audio component is degraded. | Use refresh, reconnect, run-watchdog, or diagnostics as shown by the app. |

If an action fails with `stale_observation` or `boot_changed`, refresh status
before retrying. The Pi owns freshness checks; the app should not guess whether
old dashboard data is still safe.

## 7. Android Troubleshooting

If the app cannot find the Pi:

1. Turn Wi-Fi off and back on.
2. Confirm the phone is not using mobile-data-only mode.
3. Disable VPN, private DNS filtering, or firewall apps temporarily.
4. Try the Pi IP address instead of hostname.
5. Confirm the Pi API port is reachable from another device on the same LAN.

If `audiopi.local` does not work:

1. Try the Pi IP address.
2. Confirm the router supports local hostname or mDNS resolution.
3. Continue with manual IP entry; mDNS discovery is not required for Phase 3.

If controls are disabled:

1. Check whether the app shows `unauthorized`.
2. Re-enter the bearer token if required.
3. Refresh status.
4. Wait for any running operation to finish.

If the app shows `wrong_device`:

1. Do not continue with repair controls.
2. Confirm the hostname or IP belongs to the Pi audio controller.
3. If the Pi was reinstalled intentionally, pair again only after confirming the
   new `deviceId` and `controllerInstanceId` are expected.

If Spotify does not appear as expected:

1. Open Spotify from the app or directly from Android.
2. Use Spotify's device picker to choose `Indoor`, `Outdoor`, or `Whole House`.
3. Return to the app and refresh status.
4. Use diagnostics only if the Pi reports a Spotify endpoint or route problem.

If a speaker is disconnected:

1. Refresh status.
2. Use reconnect for the affected speaker if the app offers it.
3. Run watchdog if the route still reports degraded.
4. Check diagnostics if reconnect or watchdog fails.

## 8. Manual Setup Handoff Notes

- Android is a control and status companion only.
- Android must not stream audio, bridge Bluetooth, or own Spotify playback
  routing.
- Speaker MAC addresses, sink names, service names, and command flags must stay
  on the Pi side behind allowlisted ids.
- Do not treat fixture values from the test contract as the final deployment
  identity.
- A hostname or IP change is acceptable when the saved identity tuple still
  matches.
