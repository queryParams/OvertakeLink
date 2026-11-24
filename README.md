# OvertakeLink

**English** · [Русский](README.ru.md)

**Android Auto on stock QDLink head units** — nothing to install on the head unit.

Many Geely cars can only mirror the phone screen through the QDLink app out of the box. OvertakeLink
replaces that app on the phone and shows full Android Auto on the car screen instead of a mirror:
maps, music, calls, the Assistant — with touch control.

- nothing is installed on the head unit, the car is not modified;
- works while the phone is locked and in your pocket;
- the Assistant — with “Hey Google” or the microphone icon on the car screen;
- sound goes to the car over Bluetooth, as usual.

> OvertakeLink is an independent hobby project, not affiliated with Neusoft (the QDLink developer),
> Geely or Google. QDLink, Geely and Android Auto are trademarks of their owners and are mentioned
> only to indicate compatibility.

> **Use at your own risk.** OvertakeLink is provided “as is”, without any warranty (see
> [LICENSE](LICENSE)). The authors are not liable for the consequences of its use, including
> malfunctions of the phone, head unit or car and situations on the road. Keep your eyes on the
> road: set up the app and Android Auto only while parked.

## Installation

1. **Download** the latest version from [Releases](../../releases): the `OvertakeLink-x.y.z.apk` file.
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
   - cable and default app — as in step 4 below;
   - Bluetooth: the phone must be connected to the car, as for music.
4. **Connect the phone to the car’s USB port with a cable.** Android will ask which app should open
   “QDriveLink” (on some cars “AndroidLink”) — choose OvertakeLink and tick “Always”. It’s best to uninstall the stock QDLink app from the phone.
5. **Open the QDLink app on the head unit** — Android Auto is shown in it. OvertakeLink does not mirror the phone
   screen: the head unit shows Android Auto running on the phone.
6. The first time, Android Auto will ask you on the phone to accept its terms and allow
   notification access — do it while parked.

After that it’s simple: get in, plug in the cable, open QDLink on the head unit — Android Auto is on the car screen.

**Steering wheel buttons.** The car sends “next / previous” to whichever audio source is selected
on the head unit: they switch tracks only while the head unit’s stock Bluetooth player is open. The
steering wheel voice button works only if the car passes it to the phone over Bluetooth; if not, call the
Assistant with “Hey Google” or the microphone icon on the car screen.

**Updates:** when opened, OvertakeLink checks GitHub for new versions (including pre-releases) and shows a “Download and install”
button on the main screen: Android’s download manager fetches the new version (progress is in the notification
shade, the app can be in the background; if the download manager is disabled on the phone, the app downloads it
itself), and the installer opens while OvertakeLink is on screen. The first time, Android will ask you to allow
OvertakeLink to install apps. If you cancel the installation, the file stays and the button becomes “Install” — no
second download. The new version installs over the old one, settings are kept.

**Interface language** — English or Russian, following the phone language.

**If something goes wrong:** OvertakeLink → “Log” → “Save log” (with the cable connection the log is also
saved automatically after every drive to `Downloads/OvertakeLink`; with GAP Smart Tool, only with this button). Attach it to your problem report. The log itself is
in Russian — that’s fine.

**Questions, bugs, wishes** — in [Issues](../../issues). Describe the car (model, year, head unit
firmware), the phone and attach the log. Pull requests are disabled: all discussion goes through
Issues. The log contains the car’s Bluetooth address — if you don’t want to publish it, remove it
from the file (more in [Privacy](PRIVACY.md)).

## Requirements

**Phone**
- **Android 11 or newer** and the Android Auto app from Google Play.
- Any processor from 2018 or newer, **4 GB of RAM or more** (this is Android Auto’s own
  requirement; OvertakeLink takes about 200 MB).
- The load depends on the video mode (Settings → “Video to the car screen”):
  - **compatible** (default) — the phone decodes and re-encodes the video; needs a hardware
    H.264 codec (every modern phone has one);
  - **direct** — the phone just forwards Android Auto’s ready video: almost no load from
    OvertakeLink, lower latency, sharper picture. Try it — if the car screen stays black, switch
    back to compatible.
- Bluetooth — for sound and steering wheel buttons. Internet is needed by maps and Android
  Auto’s online services, and to check for and download OvertakeLink updates.
- A USB cable that carries data (not charge-only). The phone charges from the car’s USB, but
  during navigation with the screen on the battery may slowly drain.

**Car**
- A stock head unit with QDLink that the phone connects to **over USB**.
- Tested: **Geely Atlas Pro 2023**, head unit HW NL3B (1920×590 screen), firmware
  SWONL3B0202H5177.00093 (“firmware 93”). This head unit speaks QDLink with `!BIN` packets.
- Other head units with the same QDLink format will most likely work too: OvertakeLink takes the
  screen size from the head unit itself.
- Head units with the newer QDLink protocol (`5A5A` + JSON, e.g. Geely Okavango) are **experimental**
  since 0.9.7: the protocol follows the stock QDLink code and is tested only in the emulator. If you
  have such a head unit, please send the app log in [Issues](../../issues).
- Direct mode depends on the head unit decoder: if the car screen stays black, try “Direct 720p”,
  then “Direct 480p”; if that doesn’t help, choose “Compatible”. On the Geely Atlas Pro 2023 direct
  480p shows a picture; 720p and 1080p were reworked in 0.9.1 and are still being tested; compatible
  works.
- If the picture now and then drops to a black screen or tears, lower the quality (1080p → 720p → 480p): the head
  unit may not keep up with drawing heavy frames. Quality is set with “Video to the car screen” and only
  for the cable connection: the new one takes effect once you unplug the cable and plug it back in. With output to
  GAP Smart Tool over Wi-Fi quality has its own setting, “Picture quality in GAP Smart Tool”: 1080p (as on the screen,
  the default), 720p or 480p; the new one takes effect the next time GAP Smart Tool connects, right away after “Stop” →
  “Turn on”.

**Tested:** Xiaomi 14 Ultra (Android 15, HyperOS) + Geely Atlas Pro 2023; Android 11 and 15 emulators with a
head unit simulator. Other combinations — please report how it went.

## Terms of use

OvertakeLink shows these terms each time you open it; it connects to the car only after they have been accepted.

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
2. OvertakeLink speaks the QDLink protocol (`!BIN` format, packets padded to 512-byte blocks; newer head units — `5A5A` + JSON) — the head unit
   thinks it is talking to the stock app.
3. The built-in Android Auto receiver (from [Open Headunit](https://github.com/andreknieriem/headunit-revived))
   connects to Android Auto on the same phone through its “head unit server”.
4. Android Auto video goes to the car screen, touches on the car screen go back to Android Auto.

Video modes:

- **Direct 1080p / 720p / 480p** — H.264 from Android Auto is passed to the head unit as is, without
  re-encoding: minimal latency and battery use. The head unit always stretches the frame to the whole
  screen, so AA draws it with compensation for stretched pixels — proportions on screen are right.
  The bigger the frame, the sharper the picture.
- **Compatible** — AA draws 1080p with the interface in the car screen strip; the phone decodes the
  video and re-encodes that strip pixel for pixel for the car screen at the bitrate the head unit requests (at least 2 Mbit/s). Slightly more
  latency, but the stream is the same as the stock QDLink one and independent of the head unit
  decoder’s capabilities.

### Output to GAP Smart Tool over Wi-Fi

Instead of QDLink, the picture can go to [GAP Smart Tool](https://github.com/queryParams/gap-smart-tool) on the head
unit (Settings → “Where to show Android Auto” → “In GAP Smart Tool over Wi-Fi”; chosen by hand, the cable is then only
for charging). The phone shares its hotspot, the head unit joins it, and GAP Smart Tool connects to its network gateway —
the phone — on TCP port 47621. OvertakeLink starts Android Auto in direct mode (1080p, 720p or 480p from “Picture
quality in GAP Smart Tool”) for the screen area GAP Smart Tool reports (1724×640 on the Atlas Pro): AA draws its
interface in that shape with margins around it, H.264 goes over as is, touches come back. After a phone restart, open
OvertakeLink once; after “Stop”, tap “Turn on”. Needs GAP Smart Tool 2.1.0 or newer: it acknowledges every frame, so
when Wi-Fi stalls, Android Auto on the phone slows down by itself and the picture does not flicker.
Protocol: `gap/GapProtocol.java` (a copy lives in GAP Smart Tool).

## Building

You need JDK 17+ and the Android SDK. `build.sh` looks for them in `~/.local/opt` (adjust the paths):

```sh
./build.sh                              # debug APK (playstore flavor)
./build.sh assemblePlaystoreRelease     # release; signing from key.properties (not in the repository)
./build.sh testPlaystoreDebugUnitTest   # unit tests
```

Releases are built from the `playstore` flavor, which leaves out the “offline VPN” of Open Headunit’s `github`
flavor.

A debug build (and a release built with `-PolSim`) can connect to a head unit simulator over TCP
instead of USB: `UsbAttachActivity` with the extra `sim=host:port`. This way the whole path — from
the QDLink protocol to the picture on the “car screen” — is tested in the Android emulator without
a car.

## Known limitations

- The Android Auto head unit server is a developer mode; Google may change its behavior in new
  AA versions.
- The steering wheel voice button works only if the car passes it to the phone over Bluetooth; “next / previous” work only while the head
  unit’s Bluetooth player is selected. Buttons the car doesn’t pass over Bluetooth are not available
  to the phone.
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
