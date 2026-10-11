# Carlink — Behavior, Limitations & Design Notes

Detailed explanations that back the summary in the [project README](../../README.md).
Treat these as best-effort findings, not guarantees — see the accuracy note in the README.

## Design principles (projection streams)

Projection streams are **live UI state**, not video playback. Do not buffer, pace,
preserve, or "play" frames. Late frames must be dropped; corruption must trigger a reset.

CarPlay / Android Auto H.264 is not media — it is a real-time projection of UI state.
Correctness is defined by latency, not completeness. Buffers create corruption; queues create lies.

| Video | Audio |
|---|---|
| Represents live UI state | Represents continuous time |
| Late == invalid | Late == fill |
| Drop aggressively | Buffer aggressively |
| Reset on corruption | Never stall |
| Never wait | Never block video |

Video is a best-effort, disposable representation of UI state. Audio is a continuous time
signal that must never stall. Video may drop, audio may buffer — neither may block the other.

## Limitations

### Cluster song metadata can go blank

Due to an AAOS bug, song metadata on the Instrument Panel Cluster can go blank. Simplified:
the app creates a media session "token" and hands it to the OS. The native media player and
the cluster both read song info from it. The boot/auto-launch, head-unit reboot, and
adapter config-change cases have been fixed and now repopulate the cluster card correctly.

The one remaining case is **force-stopping the app and relaunching it**: the OS-side
CarLauncher controller is nulled when the session is destroyed and never rebound (an AOSP
limitation), so the cluster keeps holding the dead controller and goes blank. No known
third-party fix exists. Emulator testing shows Apple Music and Spotify hit the same stale
card, confirming it is a platform limitation, not app-side.

### Navigation turn-by-turn

On **stock** adapter firmware, the firmware strips the rich nav details iPhones provide and
narrows them to basic maneuvers. The [custom adapter firmware](#custom-adapter-firmware)
restores full iAP2 route information, so CarPlay gets complete maneuver/route data. CarPlay
itself does not send ready-made navigation icons, so the app generates them on-device from
the recovered iAP2 geometry (including correct roundabouts drawn from the real junction
arms). Android Auto does provide maneuver images, which are forwarded to the cluster — they
are more detailed and accurate.

### Cluster navigation icons (gminfo3.7 only) — for forks / other developers

This mechanism only does anything useful on the **gminfo3.7** platform (Info 3.7, AAOS 12,
e.g. the author's Silverado). The app gets its own maneuver icons into the gminfo3.7 cluster
by claiming a content-provider "hook" — the authority
`com.google.android.apps.automotive.templates.host.ClusterIconContentProvider` — that GM's
Templates Host references but never registers (GM leaves it open/unclaimed). The app
registers a provider on that authority so the cluster's icon calls land on it and receive
the forwarded Android Auto maneuver bitmaps.

Because this repo's author was the first developer to upload a bundle claiming that
authority, Google reserved it to them: Google enforces that a content-provider authority is
globally unique and locked to its first publisher. The catch for everyone else: GM exposes
only that **one** open hook and it is already claimed, so no other developer's bundle can
claim the same authority — Google rejects it on upload.

The code is left in place so another dev can still build and ship from this repo with one
change: a fork changes only its `applicationId`, and the `play` flavor automatically derives
a unique authority (`<applicationId>.ClusterIconContentProvider`) to get past the Play
Console check. But GM AAOS only ever calls the real GM authority, so that derived provider
is never invoked on the head unit — it lets a fork upload, it does not deliver icons to the
cluster. **Net result: a fork can publish, but without the GM authority the cluster shows
text navigation only — no maneuver icons.**

### GM VCU platform (Bosch VCUNH1)

Vehicles running the GM VCU radio (Bosch VCUNH1 — most EVs and newer Fuel/ICE vehicles) on
GM AAOS 14+ do not use any icon provided by the app. The hook itself is firmware-verified to
be open/claimable on VCUNH1 (it ships the same unmodified Google Templates Host), but it is
**bypassed**: GM's VMSPlugin force-renders the cluster glyph from a maneuver-type enum
(`setManeuverType()`), so the app's icon is masked regardless of who owns the authority.
GM VMSPlugin still passes the icons, but the cluster ECU ignores them and renders from its
own internal icon set. Earlier VCU vehicles on AAOS 12 or 13 may have behaved differently
before being upgraded.

### Heads-Up Display (HUD)

The author's truck has no HUD and cannot test it. Some Silverado and Hummer EV users report
the HUD does show navigation. Testing is limited to the author's Silverado software; GM and
others can change how the HUD is driven, so behavior is not guaranteed.

## "Not Supported" USB toast on GM AAOS 14+

GM AAOS 14+ radios (e.g. VCUUM-platform VCU units) show an **"Unsupported device"** toast for
the adapter and never let an app claim it, unless the adapter's USB identity has already been
seen as a device type GM's USB admission code recognizes. This is host-OS filtering, not an
adapter fault, and it's why the old Carlinkit **UDisk mode** toggle worked around it.

Confirmed directly against a decompile of a real VCUUM-470.3 `services.jar` (SHA-1-matched to
a live system image) and reproduced live: `UsbHostManager.usbDeviceAdded()` rejects the device
*before* it's ever added to the device list — so the rejection is invisible to every app, not
just this one, and `UsbManager.requestPermission()` is never reached because the app-level
permission step is structurally unreachable for a rejected device.

The admission allow-list recognizes a fixed set of device types: mass storage, MTP/ADB/phone-
tablet interfaces, Android Auto's specific VID/PID pattern, Apple iPhone, GM's own BCS/Aptiv
hubs, and generic USB hubs. The adapter's actual working mode isn't on that list, so on first
attach with an unrecognized serial number, GM rejects it and shows the toast. It does, however,
remember the serial number of the most recently admitted-then-removed device in a single
in-memory slot, and will silently re-admit a reconnecting device — skipping the toast — if the
same serial comes back attached to a recognized class/type. That slot is overwritten by the
next admitted device's removal and is lost whenever `system_server` restarts.

**The gate itself is disabled entirely on debuggable builds** (`ro.debuggable=1`) — this, not
any USB driver or hardware difference, is why the adapter can be claimed on a userdebug/dev
build (e.g. an AVD-style image) but not on GM's real production (`user`-type) firmware. A
production vehicle ships the same enforced gate as a production-build VM.

**Why UDisk mode fixed it:** the adapter's serial number is shared across units. Toggling UDisk
mode makes the adapter re-enumerate as mass storage — a recognized type — so GM admits it and
remembers that serial when the storage device is removed. Toggling UDisk back off re-enumerates
the *same serial* into the real protocol mode; GM recognizes it as a reconnect of an
already-known serial and lets it through without the toast, so an app can prompt the user to
claim it.

## Custom adapter firmware

Adapter-side binaries were patched to correct two problems and require custom firmware on
the adapter itself. Not necessary to run, but it gives a better, closer-to-stock experience:

1. Carlinkit stripped away the rich iAP2 navigation data received from the iPhone — corrected
   so full iAP2 route information is now forwarded and received.
2. Carlinkit's Android Auto path incorrectly parsed received vehicle GPS NMEA when the app
   forwarded it to the phone — patched so correct, accurate NMEA reaches the Android phone.

Firmware and details live in the centralized adapter repo:
[CPC200-CCPA_resources](https://github.com/lvalen91/CPC200-CCPA_resources).

## Audio routing & troubleshooting

Before reporting audio issues:

1. **Disconnect and forget** the phone and vehicle from each other's Bluetooth. The adapter
   defaults to routing audio **through the adapter** for both microphone input and audio
   output. Make sure you granted the app microphone access.
2. **Steering-wheel voice/call control does not work** — that is a system-level app feature
   and this is not that. If you want steering-wheel voice controls, ignore step 1 and set the
   in-app adapter audio routing to **Bluetooth**; the phone and vehicle then stay connected
   for all audio-related events.

## Testing scope & hardware

- Target platform and only test hardware: a 2024 Silverado **gminfo3.7** Intel AAOS radio.
- Some simulated testing was also done on a GM emulator running **GM AAOS 14 (VCUUM
  platform, build 470.3)**.
- The primary phone is an **iPhone**, so CarPlay has the most tuning and testing. A Google
  Pixel 10 is used for basic Android Auto functionality, not real-world day-to-day testing.
- Optimized for video and audio performance on gminfo3.7. Compatibility on anything else is
  **unverified and should be treated as untested.** Fork and optimize for your own needs.

gminfo platform details: [gminfo resources](https://github.com/lvalen91/gminfo_resources).
