# Carlink

A **native** Kotlin reimplementation of the original [Flutter-based app](https://github.com/lvalen91/carlink_flutter).
Built for personal use and shared so others can use it. Please don't expect or demand
support — I'll help where I can.

**[XDA Developer Forums thread](https://xdaforums.com/t/carlink.4774308/)** · Report issues
on GitHub or the forum.

## Requirements

- AAOS 10 (Android 10) or higher
- [Carlinkit CPC200-CCPA](https://www.carlinkit.com/ccpa) adapter on firmware 2025.10

## Status

Work in progress — something is always changing. Updates that ship without code changes are
usually new documentation or corrections.

Target and only hardware-tested platform: a 2024 Silverado **gminfo3.7** Intel AAOS radio,
with an iPhone as the primary device (CarPlay is the most tuned). Some simulated testing was
also done on a GM emulator running **GM AAOS 14 (VCUUM platform, build 470.3)**. Everything
else is **untested**. See [Testing scope](documents/reference/app-notes.md#testing-scope--hardware).

## Installing

How you install depends on whether your head unit gives you ADB access.

### GM production vehicles (no ADB)

GM's shipping radios expose **no ADB** and run only apps delivered through Google Play. There
is no public Play listing for this app, so each user installs it through **their own Google
Play Developer Account**:

1. Enroll in the [Google Play Console](https://play.google.com/console/) (one-time US$25 fee).
2. Give the app a package name you own — change `ownerApplicationId` in `app/build.gradle.kts`
   (a fork must not reuse `zeno.carlink`), then build the `play` bundle (AAB).
3. Create an app in the Console and upload that bundle to an **Internal Testing** track.
4. Add your own Google account as an internal tester, accept the opt-in link, then install from
   the Play Store **on the head unit** while signed into that account.

The build is signed with **your** upload key/certificate, not the author's — that is expected
and required. If you are new to the Play Console, search for a current "upload an app to an
Internal Testing track" tutorial; the process is standard Android publishing and not specific
to this app.

### AAOS platforms with ADB access

If your platform exposes ADB, install the APK with the installer attributed to the Play Store:

```
adb install -i com.android.vending app-sideload-release.apk
```

The `-i com.android.vending` flag records Google Play as the installing package. AAOS considers
the install source when deciding what may run while driving, so attributing the install to Play
makes the system honor the app's **Distraction Optimization** declarations — showing and running
it normally — instead of treating a plain sideload as non-optimized and restricting it. A signed
`sideload` APK is attached to each [GitHub Release](../../releases), or you can build one yourself.

## Key limitations

Full explanations in **[app notes](documents/reference/app-notes.md)**:

- **Cluster song metadata** can go blank after force-stop + relaunch — an AOSP limitation
  with no third-party fix. All other cases are fixed.
  [Details](documents/reference/app-notes.md#cluster-song-metadata-can-go-blank)
- **Navigation turn-by-turn** is basic on stock adapter firmware; the
  [custom firmware](#custom-adapter-firmware) restores full iAP2 route data, and the app
  renders CarPlay maneuver icons on-device.
  [Details](documents/reference/app-notes.md#navigation-turn-by-turn)
- **Cluster navigation icons** work only on gminfo3.7 and only for this build — forks can
  publish but won't deliver cluster icons (Google locks the GM provider authority to its
  first publisher).
  [Details](documents/reference/app-notes.md#cluster-navigation-icons-gminfo37-only--for-forks--other-developers)
- **GM VCU (Bosch VCUNH1)** vehicles ignore app-supplied cluster icons entirely.
  [Details](documents/reference/app-notes.md#gm-vcu-platform-bosch-vcunh1)
- **HUD** is untested (author's truck has none); some Silverado/Hummer EV users report nav shows.
  [Details](documents/reference/app-notes.md#heads-up-display-hud)

## Custom adapter firmware

Optional. Adapter-side binaries were patched to (1) forward the full iAP2 navigation data
Carlinkit otherwise strips, and (2) fix Android Auto's incorrect parsing of forwarded vehicle
GPS NMEA. Not required, but gives a closer-to-stock experience. Firmware lives in
[CPC200-CCPA_resources](https://github.com/lvalen91/CPC200-CCPA_resources).
See [notes](documents/reference/app-notes.md#custom-adapter-firmware).

## Audio

If audio misbehaves, first disconnect and forget the phone/vehicle from each other's
Bluetooth and grant the app microphone access — the adapter routes audio through itself by
default. Steering-wheel voice/call control is a system feature this app can't provide; for it,
set in-app adapter routing to Bluetooth instead. See
[audio routing](documents/reference/app-notes.md#audio-routing--troubleshooting).

## Screenshots

From the Android emulator with USB passthrough for the CPC200-CCPA.

| CarPlay (iPhone) | Android Auto (Pixel 10) | Main app |
|---|---|---|
| ![CarPlay](screenshots/Carplay.png) | ![Android Auto](screenshots/Aauto.png) | ![Main page](screenshots/MainPage.png) |

**Adapter configuration** — applied on reboot via *Apply & Restart*:

![Audio](screenshots/adapter_config-Audio.png)
![Visual](screenshots/adapter_confid-Visual.png)
![Misc](screenshots/adapter_config-Misc.png)

**App settings** — control what is hidden/shown to give the projection stream more room:

![Display mode](screenshots/Settings-DisplayMode.png)

**Log export & levels** — export logs to a file via the Android documents picker (no extra
folder permissions needed; can save to attached USB, subject to OS restrictions). Multiple
log levels narrow troubleshooting to the area you need:

![File logging](screenshots/File_Logging.png)
![Log levels](screenshots/LogLevels.png)

## Documentation

The app is **self-contained** — this repo builds and runs on its own. The docs in this repo
are the primary reference:

| Topic | Where | What it is |
|---|---|---|
| App behavior, limitations & design notes | [documents/reference/app-notes.md](documents/reference/app-notes.md) | The detail behind this README |
| Reference docs index | [documents/reference/](documents/reference/) | Emulator, cluster, and platform notes |

Accuracy isn't guaranteed, but it's the most detailed, centralized source short of the
adapter/GM source itself. If you have direct adapter/GM-radio source, there's a site that
will gladly publish it anonymously.

## Supporting & reference material

Separate repos — **not dependencies of this app** — that back the research behind it or
provide background:

| Repo | What it is |
|---|---|
| [CPC200-CCPA_resources](https://github.com/lvalen91/CPC200-CCPA_resources) | Work on the Carlinkit CPC200-CCPA adapter (firmware, reverse engineering) |
| [gminfo_resources](https://github.com/lvalen91/gminfo_resources) | GM head-unit / gminfo3.7 AAOS platform reference |
| [carlink_flutter](https://github.com/lvalen91/carlink_flutter) | The original Flutter app this one reimplements |
| [XDA Forums thread](https://xdaforums.com/t/carlink.4774308/) | Discussion and support; report issues here or on GitHub |

## Credits

Projects that started this and provided insight, inspiration, and a lot of help:

- [carplay by abuharsky](https://github.com/abuharsky/carplay) — original Android implementation
- [node-CarPlay by rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay) — protocol reverse engineering
- [LIVI by f-io](https://github.com/f-io/LIVI) — Linux (Raspberry Pi) and macOS implementation
- [pycarplay by electric-monk](https://github.com/electric-monk/pycarplay) — Python implementation
