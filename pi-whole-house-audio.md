# Pi Whole-House Spotify Audio Setup

This guide sets up a Raspberry Pi as a Spotify Connect audio bridge for two
Bluetooth speakers:

- `Indoor`: plays to the indoor Bluetooth speaker only
- `Outdoor`: plays to the outdoor Bluetooth speaker only
- `Whole House`: plays to both Bluetooth speakers at the same time

Spotify will show these as three separate devices in the Spotify device picker.
Spotify can play to only one of them at a time.

## Before You Start

You need:

- Raspberry Pi 3, 4, 5, or Zero 2 W
- Raspberry Pi OS Lite 64-bit, Bookworm
- Two Bluetooth speakers
- Spotify Premium account
- Wi-Fi or Ethernet network shared by the Pi and the phone running Spotify
- Recommended: one USB Bluetooth 5.0 dongle

Spotify Premium is required because Spotify Connect playback through `librespot`
does not work with free Spotify accounts.

The USB Bluetooth dongle is strongly recommended. With two speakers on one
Bluetooth radio, `Whole House` playback may stutter or lose sync. The most
reliable setup is one Bluetooth radio per speaker:

- Pi built-in Bluetooth radio: one speaker
- USB Bluetooth dongle: the other speaker

## Assumptions to Confirm

Confirm these before treating this as final setup documentation for the Android
and Pi project:

- The Android app is expected to use Spotify's device picker or Spotify Connect,
  not stream audio directly to the Pi.
- The desired public device names are exactly `Indoor`, `Outdoor`, and
  `Whole House`.
- The Pi username is `pi`. If a different username is used, commands that include
  `pi` must be updated.
- Each Bluetooth speaker should be paired to only one Pi Bluetooth adapter.
- The chosen USB Bluetooth dongle is Linux-compatible on Raspberry Pi OS
  Bookworm without extra drivers.
- The user understands that sink names are generated locally from Bluetooth
  MAC addresses and must be copied exactly.

## 1. Flash Raspberry Pi OS

1. Open Raspberry Pi Imager.
2. Choose Raspberry Pi OS Lite 64-bit, Bookworm.
3. In advanced settings:
   - Set the hostname, for example `audiopi`.
   - Enable SSH.
   - Configure Wi-Fi.
   - Set the username to `pi`.
4. Boot the Pi.
5. SSH into the Pi.
6. Update the system:

```bash
sudo apt update
sudo apt full-upgrade -y
sudo reboot
```

Reconnect over SSH after the Pi reboots.

## 2. Install the Audio Stack

Install PipeWire, Bluetooth audio support, and PulseAudio compatibility tools:

```bash
sudo apt install -y pipewire pipewire-pulse wireplumber libspa-0.2-bluetooth pulseaudio-utils
```

Enable user services to keep running after SSH logout:

```bash
sudo loginctl enable-linger pi
```

Verify PipeWire is running:

```bash
systemctl --user status pipewire
```

`libspa-0.2-bluetooth` is required. Without it, Bluetooth speakers may pair and
connect but not appear as usable audio outputs.

If PulseAudio is installed as the active audio server, remove or disable it.
PipeWire replaces PulseAudio for this setup.

## 3. Install Librespot

Install Raspotify, then disable its default service. This uses the Raspotify
installer only as a convenient way to install the `librespot` binary.

```bash
curl -sL https://dtcooper.github.io/raspotify/install.sh | sh
sudo systemctl disable --now raspotify
```

Confirm `librespot` is installed:

```bash
which librespot
```

Expected path:

```text
/usr/bin/librespot
```

## 4. Pair the Bluetooth Speakers

Pair one speaker at a time. If you are using a USB Bluetooth dongle, pair each
speaker to a different adapter.

Start Bluetooth setup:

```bash
bluetoothctl
```

Inside `bluetoothctl`, list available Bluetooth adapters:

```text
list
```

If the USB dongle is connected, you should see two adapters. Select the adapter
you want to use for the first speaker:

```text
select <ADAPTER_MAC>
power on
agent on
default-agent
scan on
```

Put the first speaker into pairing mode. When its MAC address appears, pair and
trust it:

```text
pair <SPEAKER_MAC>
trust <SPEAKER_MAC>
connect <SPEAKER_MAC>
```

Repeat the same process for the second speaker. If using the recommended dongle
setup, run `select <OTHER_ADAPTER_MAC>` before pairing the second speaker.

Exit Bluetooth setup:

```text
exit
```

Important: do not pair both speakers first and then decide which adapter should
own them. Pairing order matters because the Pi stores each speaker under the
Bluetooth adapter that paired with it.

## 5. Record the Sink Names

After both speakers are connected, list the available audio sinks:

```bash
wpctl status
pactl list short sinks
```

Find the two Bluetooth sink names. They usually look similar to this:

```text
bluez_output.AA_BB_CC_DD_EE_FF.1
bluez_output.11_22_33_44_55_66.1
```

Record the exact sink names here before continuing:

```text
INDOOR_SINK_NAME=
OUTDOOR_SINK_NAME=
```

Use the exact names shown by `pactl`. Do not change punctuation, capitalization,
or spacing.

If a speaker is re-paired later, its sink name may change. If that happens,
update every config file that references the old sink name.

## 6. Create the Whole House Sink

Create the PipeWire config directory:

```bash
mkdir -p ~/.config/pipewire/pipewire.conf.d
```

Create this file:

```bash
nano ~/.config/pipewire/pipewire.conf.d/combine.conf
```

Paste this config, replacing `<INDOOR_SINK_NAME>` and `<OUTDOOR_SINK_NAME>` with
the exact sink names from the previous step:

```text
context.modules = [
  { name = libpipewire-module-combine-stream
    args = {
      combine.mode = sink
      node.name = "whole_house"
      node.description = "Whole House"
      combine.latency-compensate = true
      combine.props = { audio.position = [ FL FR ] }
      stream.rules = [
        { matches = [ { media.class = "Audio/Sink" node.name = "<INDOOR_SINK_NAME>" } ]
          actions = { create-stream = { } } }
        { matches = [ { media.class = "Audio/Sink" node.name = "<OUTDOOR_SINK_NAME>" } ]
          actions = { create-stream = { } } }
      ]
    }
  }
]
```

Restart PipeWire:

```bash
systemctl --user restart pipewire wireplumber
```

Confirm `Whole House` appears as a sink:

```bash
wpctl status
```

## 7. Create the Spotify Connect Services

Create config directories:

```bash
mkdir -p ~/.config/systemd/user
mkdir -p ~/.config/librespot
```

Create the systemd user service:

```bash
nano ~/.config/systemd/user/librespot@.service
```

Paste:

```ini
[Unit]
Description=librespot (%i)
After=pipewire.service network-online.target

[Service]
EnvironmentFile=%h/.config/librespot/%i.env
ExecStart=/usr/bin/librespot --backend pulseaudio --name "${SPEAKER_NAME}" --bitrate 320 --disable-audio-cache
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

Create the indoor endpoint config:

```bash
nano ~/.config/librespot/indoor.env
```

```text
SPEAKER_NAME=Indoor
PULSE_SINK=<INDOOR_SINK_NAME>
```

Create the outdoor endpoint config:

```bash
nano ~/.config/librespot/outdoor.env
```

```text
SPEAKER_NAME=Outdoor
PULSE_SINK=<OUTDOOR_SINK_NAME>
```

Create the combined endpoint config:

```bash
nano ~/.config/librespot/both.env
```

```text
SPEAKER_NAME=Whole House
PULSE_SINK=whole_house
```

Replace the sink placeholders with the exact sink names from step 5.

Enable and start all three Spotify Connect endpoints:

```bash
systemctl --user daemon-reload
systemctl --user enable --now librespot@indoor librespot@outdoor librespot@both
```

Check service status:

```bash
systemctl --user status librespot@indoor
systemctl --user status librespot@outdoor
systemctl --user status librespot@both
```

## 8. Test Spotify Playback

Open Spotify on a phone or computer connected to the same network as the Pi.

In the Spotify device picker, confirm these devices appear:

- `Indoor`
- `Outdoor`
- `Whole House`

Test each endpoint:

- Select `Indoor`; only the indoor speaker should play.
- Select `Outdoor`; only the outdoor speaker should play.
- Select `Whole House`; both speakers should play.

## 9. Tune Whole House Sync

If `Whole House` playback has an echo, delay the speaker that plays earlier.

List Bluetooth cards:

```bash
pactl list cards short
```

Set a latency offset:

```bash
pactl set-port-latency-offset <CARD> <PORT> <OFFSET_USEC>
```

Example:

```bash
pactl set-port-latency-offset bluez_card.AA_BB_CC_DD_EE_FF a2dp-sink 40000
```

`40000` means 40 ms.

Adjust until the echo disappears. Reboot afterward and confirm the offset still
applies. If it does not persist, add the working command to a startup script or
systemd user service.

## 10. Add a Bluetooth Reconnect Watchdog

Outdoor speakers may be powered off or disconnected. A simple watchdog can
reconnect trusted speakers.

Create a local bin directory:

```bash
mkdir -p ~/bin
```

Create the watchdog script:

```bash
nano ~/bin/bt-watchdog.sh
```

Paste this script, replacing the placeholder MAC addresses:

```bash
#!/bin/bash
for mac in <INDOOR_MAC> <OUTDOOR_MAC>; do
  if ! bluetoothctl info "$mac" | grep -q "Connected: yes"; then
    bluetoothctl connect "$mac"
  fi
done
```

Make it executable:

```bash
chmod +x ~/bin/bt-watchdog.sh
```

Create the service:

```bash
nano ~/.config/systemd/user/bt-watchdog.service
```

```ini
[Unit]
Description=Reconnect trusted Bluetooth speakers

[Service]
Type=oneshot
ExecStart=%h/bin/bt-watchdog.sh
```

Create the timer:

```bash
nano ~/.config/systemd/user/bt-watchdog.timer
```

```ini
[Unit]
Description=Run Bluetooth reconnect watchdog every minute

[Timer]
OnBootSec=30
OnUnitActiveSec=60
Unit=bt-watchdog.service

[Install]
WantedBy=timers.target
```

Enable the timer:

```bash
systemctl --user daemon-reload
systemctl --user enable --now bt-watchdog.timer
```

## Final Verification

Run these checks before considering the setup complete:

- Reboot the Pi.
- Confirm `Indoor`, `Outdoor`, and `Whole House` reappear in Spotify without SSH.
- Play audio through `Indoor`.
- Play audio through `Outdoor`.
- Play audio through `Whole House`.
- Power-cycle each speaker and confirm it reconnects within about one minute.
- Play through `Whole House` for at least one hour and check for stutter.

If `Whole House` stutters while both speakers use the same Bluetooth radio, move
one speaker to a USB Bluetooth dongle and pair it again on that adapter.

## Troubleshooting

| Problem | Likely Cause | Fix |
| --- | --- | --- |
| Bluetooth speaker connects but does not appear as an audio sink | Missing Bluetooth audio plugin | Install `libspa-0.2-bluetooth`, then restart PipeWire |
| Spotify devices disappear after SSH logout | User services are stopping | Run `sudo loginctl enable-linger pi` |
| `Whole House` does not play to both speakers | Sink name mismatch | Re-run `pactl list short sinks` and update `combine.conf` |
| Spotify does not show the Pi devices | Network discovery issue | Put phone and Pi on the same network and allow mDNS/UDP 5353 |
| Playback stutters with both speakers | One Bluetooth radio is overloaded | Use a USB Bluetooth dongle and pair one speaker per adapter |
| A speaker does not reconnect after power cycle | Speaker was not trusted or watchdog is not running | Run `trust <SPEAKER_MAC>` in `bluetoothctl` and check `bt-watchdog.timer` |

## Implementation Handoff Notes

Phase 3 Android companion and Pi local API details are now tracked in
`phase3-android-pi-setup-and-operations.md`. Use that file for Android
install/use flow, Pi API setup, auth assumptions, discovery, identity checks,
dashboard states, recovery actions, diagnostics, troubleshooting, and unresolved
defaults.

### 1. Summary of What This File Sets Up

This Markdown file is Raspberry Pi setup documentation. It does not define an
Android audio streaming implementation.

The Pi setup creates:

- Three Spotify Connect endpoints: `Indoor`, `Outdoor`, and `Whole House`.
- Two Bluetooth speaker connections owned by the Raspberry Pi.
- PipeWire and WirePlumber audio routing on the Pi.
- A combined PipeWire sink named `whole_house` for dual-speaker playback.
- User-level `librespot` services for Spotify Connect playback.
- A Bluetooth reconnect watchdog for trusted speakers.

The intended playback model is: Spotify sends audio to one Pi-owned Spotify
Connect endpoint, and the Pi routes that audio to the correct Bluetooth sink or
combined sink.

### 2. Companion App Scope and Non-Goals

Recommended Android direction: build a Pi Setup & Status Companion app.

The Android app should:

- Discover the Raspberry Pi on the local network.
- Show setup and health status for the Pi audio system.
- Display the configured `Indoor`, `Outdoor`, and `Whole House` endpoints.
- Map the physical indoor and outdoor speakers to the Pi's known Bluetooth
  devices and sink names.
- Trigger explicit troubleshooting actions exposed by a local Pi service.
- Help the user open Spotify and select the correct Spotify Connect device.

The Android app should not:

- Stream Spotify audio directly to the Pi.
- Act as a Bluetooth audio bridge.
- Own Bluetooth speaker pairing after the Pi has been configured.
- Reimplement PipeWire routing or Spotify Connect behavior on Android.
- Hide Pi setup failures behind automatic mobile-side retry loops.

### 3. Raspberry Pi Preparation and Required Local Service/API

The Pi remains the source of truth for audio routing and speaker state. Before
Android implementation begins, define a small local service on the Pi that the
app can call over the LAN.

Minimum service responsibilities:

- Report Pi reachability, hostname, IP address, and service version.
- Report `librespot` service state for `indoor`, `outdoor`, and `both`.
- Report whether the `Indoor`, `Outdoor`, and `Whole House` Spotify Connect
  endpoints are configured.
- Report Bluetooth adapter count and whether each expected speaker is paired,
  trusted, connected, and assigned to the expected adapter.
- Report current PipeWire sink names and whether `whole_house` exists.
- Report watchdog timer status and last reconnect attempt.
- Expose explicit repair actions, such as reconnect speaker, restart PipeWire,
  restart a `librespot` endpoint, restart the watchdog, and rescan current sink
  names.

The service should return structured responses with stable status codes and
human-readable messages. Android should consume this API instead of parsing SSH
output.

### 4. Android Setup/Status User Flow

Recommended app flow:

1. Discover the Pi on the local network, either through mDNS or manual hostname
   and IP entry.
2. Confirm the app can reach the Pi's local setup/status service.
3. Show a setup checklist:
   - Pi audio stack installed.
   - Two Bluetooth adapters available, if the USB dongle setup is expected.
   - Indoor speaker paired, trusted, connected, and assigned.
   - Outdoor speaker paired, trusted, connected, and assigned.
   - PipeWire sinks detected.
   - `whole_house` combined sink available.
   - `librespot` endpoints running.
   - Watchdog timer running.
4. Let the user label or confirm which physical speaker is `Indoor` and which is
   `Outdoor`.
5. Show ongoing status for all three playback targets.
6. Provide direct troubleshooting buttons for known Pi-side recovery actions.
7. Hand off playback selection to Spotify's device picker.

The app should make Pi state visible and recoverable. It should not imply that
audio is routed through Android.

### 5. Troubleshooting and Diagnostics Handoff Notes

Diagnostics should be grouped around the Pi-owned components:

- Network discovery: Pi unreachable, mDNS unavailable, phone and Pi on different
  networks, or blocked UDP 5353.
- Spotify Connect: one or more `librespot` services stopped, device names not
  visible, or Spotify account requirements not met.
- Bluetooth: speaker not paired, not trusted, connected to the wrong adapter, or
  out of range.
- PipeWire/WirePlumber: missing Bluetooth audio plugin, sink name changed after
  re-pairing, or `whole_house` combined sink missing.
- Watchdog: reconnect timer disabled, last reconnect failed, or speaker powered
  off.
- Whole-house playback: one Bluetooth radio overloaded, latency offset needed,
  or sink mismatch in `combine.conf`.

For each diagnostic state, the Pi service should provide:

- A machine-readable status.
- A short user-facing explanation.
- A recommended recovery action.
- Whether Android can trigger that action directly or must instruct the user to
  complete a manual Pi step.

### 6. Open Product Decision

Confirm the companion app direction before implementation begins.

Current recommendation: Android should be a Pi Setup & Status Companion app for
discovery, health checks, speaker mapping, and explicit troubleshooting actions.
It should not be designed as a Spotify streamer or Bluetooth audio transport.
