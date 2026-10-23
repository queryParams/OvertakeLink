# OvertakeLink

**English** · [Русский](README.ru.md)

**Android Auto on stock QDLink head units** — nothing to install on the head unit.

Many Geely cars can only mirror the phone screen through the QDLink app out of the box. OvertakeLink
replaces that app on the phone and shows full Android Auto on the car screen instead of a mirror:
maps, music, calls, the Assistant — with touch control.

- nothing is installed on the head unit, the car is not modified;
- works while the phone is locked and in your pocket;
- steering wheel “next / previous / pause” buttons control music, the voice button opens the Assistant on the car screen;
- sound goes to the car over Bluetooth, as usual.

> OvertakeLink is an independent hobby project, not affiliated with Neusoft (the QDLink developer),
> Geely or Google. QDLink, Geely and Android Auto are trademarks of their owners and are mentioned
> only to indicate compatibility.

> **Use at your own risk.** OvertakeLink is provided “as is”, without any warranty (see
> [LICENSE](LICENSE)). The authors are not liable for the consequences of its use, including
> malfunctions of the phone, head unit or car and situations on the road. Keep your eyes on the
> road: set up the app and Android Auto only while parked.

## Installation

1. **Download** the latest version from [Releases](../../releases): the `OvertakeLink-x.y.apk` file
   (while the project is in beta, versions are marked as pre-release — that’s expected).
   Open it on the phone. Android will ask to allow installs from this source — allow it and tap
   “Install”.
2. Install **Android Auto** from Google Play if you don’t have it yet.
3. **Open OvertakeLink.** A five-step wizard walks you through setup:
   - permissions (microphone for voice commands, notifications);
   - unrestricted battery — otherwise the phone may put the car connection to sleep;
   - **Android Auto server** — the only tricky step: in Android Auto, tap the version number
     10 times (developer mode opens), then ⋮ → “Start head unit server”. After a phone restart this
     has to be repeated — OvertakeLink will remind you on the car screen and with a notification
     on the phone (tapping it opens Android Auto);
   - Bluetooth: the phone must be connected to the car, as for music.
4. **Connect the phone to the car’s USB port with a cable.** Android will ask which app should open
   “QDriveLink” — choose OvertakeLink and tick “Always”. It’s best to uninstall the stock QDLink app.
5. The first time, Android Auto will ask you on the phone to accept its terms and allow
   notification access — do it while parked.

After that everything works by itself: get in, plug in the cable — Android Auto is on the car screen.

**Steering wheel “next / previous” buttons** go to whichever audio source is selected on the head
unit. If they don’t work, open the head unit’s stock Bluetooth player once.

**Updates:** OvertakeLink checks for new versions by itself and shows a “Download and install”
button on the main screen: the app downloads the new version and opens the installer. The first
time, Android will ask you to allow OvertakeLink to install apps. The new version installs over
the old one, settings are kept.

**Interface language** — English or Russian, following the phone language.

**If something goes wrong:** OvertakeLink → “Log” → “Save log” (the log is also saved automatically
after every drive to `Downloads/OvertakeLink`). Attach it to your problem report. The log itself is
in Russian — that’s fine.

**Questions, bugs, wishes** — in [Issues](../../issues). Describe the car (model, year, head unit
firmware), the phone and attach the log. Pull requests are disabled: all discussion goes through
Issues. The log contains the car’s Bluetooth address — if you don’t want to publish it, remove it
from the file (more in [Privacy](PRIVACY.md)).

## Requirements

**Phone**
- **Android 12 or newer** and the Android Auto app from Google Play.
- Any processor from 2018 or newer, **4 GB of RAM or more** (this is Android Auto’s own
  requirement; OvertakeLink takes about 200 MB).
- The load depends on the video mode (Settings → “Video to the car screen”):
  - **compatible** (default) — the phone decodes and re-encodes the video; needs a hardware
    H.264 codec (every modern phone has one);
  - **direct** — the phone just forwards Android Auto’s ready video: almost no load from
    OvertakeLink, lower latency, sharper picture. Try it — if the car screen stays black, switch
    back to compatible.
- Bluetooth — for sound and steering wheel buttons. Internet is only needed by maps and Android
  Auto’s online services.
- A USB cable that carries data (not charge-only). The phone charges from the car’s USB, but
  during navigation with the screen on the battery may slowly drain.

**Car**
- A stock head unit with QDLink that the phone connects to **over USB**.
- Tested: **Geely Atlas Pro 2023**, head unit HW NL3B (1920×590 screen), firmware
  SWONL3B0202H5177.00093 (“firmware 93”). This head unit speaks QDLink with `!BIN` packets.
- Other head units with the same QDLink format will most likely work too: OvertakeLink takes the
  screen size from the head unit itself. Head units with other QDLink protocol versions are not
  supported yet.
- The head unit decoder must accept H.264 1080p at 30 fps (direct mode) — if the car screen stays
  black, choose “Direct light” (720p) or “Compatible”.

**Tested:** Xiaomi 14 Ultra (Android 15, HyperOS) + Geely Atlas Pro 2023; Android 15 emulator with a
head unit simulator. Other combinations — please report how it went.

## Terms of use

On first launch OvertakeLink shows these terms and works only after they are accepted.

1. OvertakeLink is free, open-source software (GNU AGPL-3.0 license). You may use, study, modify and distribute it under the terms of that license.

2. The software is provided “as is”, without any warranty. You use it at your own risk. The authors are not liable for any damage — to your phone, head unit, car or data — or for consequences on the road.

3. Safety. Keep your eyes on the road. Set up OvertakeLink and Android Auto only while parked. Obey traffic rules.

4. OvertakeLink is an independent project and is not affiliated with Geely, Neusoft (QDLink) or Google. Names of these companies and their products are trademarks of their owners.

5. OvertakeLink uses the Android Auto developer mode. Google may change it at any time, and OvertakeLink may then stop working.

6. Data. OvertakeLink does not collect or send your data anywhere. The log is stored on your phone, and you share it yourself. The app contacts GitHub to check for and download updates.

## How it works

```
Android Auto (on the phone) ──AAP over localhost──► OvertakeLink ──QDLink over USB──► stock head unit
                                                        ▲                             │
                                                        └──── touches from screen ────┘
```

1. The head unit connects to the phone over USB as a QDLink accessory (`Neusoft / QDriveLink`);
   Android opens OvertakeLink.
2. OvertakeLink speaks the QDLink protocol (`!BIN` format, 512-byte packets) — the head unit
   thinks it is talking to the stock app.
3. The built-in Android Auto receiver (from [Open Headunit](https://github.com/andreknieriem/headunit-revived))
   connects to Android Auto on the same phone through its “head unit server”.
4. Android Auto video goes to the car screen, touches on the car screen go back to Android Auto.

Video modes:

- **Direct** — H.264 from Android Auto is passed to the head unit as is: AA draws a 1080p frame, and
  cropping to the car screen strip is written into the stream header (SPS). Minimal latency and
  battery use.
- **Direct light** — the same at 720p; the head unit scales the picture to the screen.
- **Compatible** — AA video is decoded into a window on a virtual display and re-encoded for the
  car screen. Slower, but independent of the head unit decoder’s capabilities.

## Building

You need JDK 17+ and the Android SDK. `build.sh` looks for them in `~/.local/opt` (adjust the paths):

```sh
./build.sh                              # debug APK (playstore flavor)
./build.sh assemblePlaystoreRelease     # release; signing from key.properties (not in the repository)
./build.sh testPlaystoreDebugUnitTest   # unit tests
```

Releases are built from the `playstore` flavor: Open Headunit’s `github` flavor contains an
“offline VPN” that would cut Android Auto off from the internet on the phone.

A debug build (and a release built with `-PolSim`) can connect to a head unit simulator over TCP
instead of USB: `UsbAttachActivity` with the extra `sim=host:port`. This way the whole path — from
the QDLink protocol to the picture on the “car screen” — is tested in the Android emulator without
a car.

## Known limitations

- The Android Auto head unit server is a developer mode; Google may change its behavior in new
  AA versions.
- Steering wheel buttons that the car doesn’t pass over Bluetooth are not available to the phone.
- Wireless connection (QDLink over Wi‑Fi) is not implemented yet.
- Not published on Google Play: the app relies on the Android Auto developer mode.

## License and authors

OvertakeLink is a derivative work of [Open Headunit](https://github.com/andreknieriem/headunit-revived)
(Andre Rinas and contributors), which continues [headunit](https://github.com/mikereidis/headunit)
by Michael A. Reid. Like them, it is distributed under the **GNU Affero General Public License v3.0** —
see [LICENSE](LICENSE) and [COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt).
The original Open Headunit description is in [README.OpenHeadunit.md](README.OpenHeadunit.md).

[Additional terms](ADDITIONAL-TERMS.md) under AGPL section 7 apply to OvertakeLink code: modified
versions may not be called “OvertakeLink” or presented as the original. All other AGPL rights — to
use, study, modify and distribute — remain in full.

OvertakeLink code: `app/src/main/java/io/github/queryparams/overtakelink/`; changes to Open Headunit
are marked with an “OvertakeLink” comment and visible in the git history.
