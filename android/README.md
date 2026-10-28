# Android (sideload APK)

A private, personal build of the browser version for one Android device (written for an AYN Odin 3,
should work on any arm64 phone or handheld with a WebGPU-capable GPU). It is a **wrapper, not a
port**: the game is the same `web/dist` bundle `make start` serves, running in a WebView.

What the APK holds:

- `libwenillahost.so`: `wenilla-host` cross-compiled for `aarch64-linux-android` (an executable,
  named like a library so Android installs it where it may be run).
- `assets/www/`: the `web/dist` bundle (the wasm and its `.br` sibling, the page's JS).

What it does on launch: shows a launcher screen, unpacks `www/` to app storage (first launch and
after each update), checks the game data, starts the host on `127.0.0.1:8090` against it, then
loads `http://127.0.0.1:8090/` full-screen and landscape. **Back** or **Start + Select** while it
boots opens the setup screen instead: the game folder, what the data check found, *Choose another
folder*, *Grant all files access*, *Exit*.

The page also gets `android/web/android.js` + `android.css` (the APK's copy of `index.html` only;
the dev page and the realm never load them), which turn the controller and the touch screen into
the game's own keyboard and mouse input:

| Controller | Does |
|---|---|
| Left stick | move (WASD) |
| Right stick | aim: moves a cursor the hero faces and F aims at |
| A | click at the cursor (menus, cards) |
| B | pause / back (Esc) |
| X / Y | jump / class movement spell (F) |
| D-pad ← ↑ → | level-up card 1 / 2 / 3 |
| D-pad ↓ | free / lock the camera (C) |
| L1 / R1 | zoom out / in |
| Start | Enter World / play again (Enter) |
| Select, or the system Back | the side menu |

The side menu: Resume, Pause, touch controls (auto / on / off: auto shows them on the first touch
and hides them again when the controller is used), an FPS counter, render scale (100 / 85 / 70 /
50 %, fewer pixels for more frames), the controller layout, Reload, game folder and setup, Exit.
Touch controls: a joystick bottom-left, Jump and F bottom-right, zoom, pause and menu on the right
edge; touching the game itself turns the hero toward your finger.

**The game data is never in the APK.** Copy the WoW 1.12.1 `Data` folder (the one
`make fetch-data` puts in `wow-data/Data`) to the device yourself.

## Build

```bash
make build                                  # the wasm build → web/dist (once)
export ANDROID_HOME=/path/to/android-sdk    # with platforms;android-35, build-tools;35.0.0 and an NDK
make apk                                    # → android/azeroth-survivors.apk
```

SDK pieces, with Android's command-line tools:
`sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "platform-tools"`.
JDK 17 or newer. The Gradle wrapper (`android/gradlew`) fetches Gradle and the Android plugin.
The APK is signed with the local debug key: fine for sideloading, not for a store.

## Install and copy the data

```bash
adb install -r android/azeroth-survivors.apk
```

Copy the `Data` folder anywhere on the device or its SD card (a file manager, a USB cable, adb),
open the app and tap **Choose game folder**. That is Android's own folder picker; pick the folder
holding `Data/`, or `Data` itself. The app then asks for **All files access** (the same thing WoW
Forever for Android asks for, for the same reason: the game server opens the files by path) and
starts. The choice is remembered; *Choose another folder* appears whenever it stops finding MPQs.

Without granting any permission, push `Data` into the app's own folder instead:

```bash
adb push wow-data/Data /sdcard/Android/data/survivors.android.local/files/
```

When nothing has been picked, the app also looks in its own folder, `/sdcard/wow-data`,
`/sdcard/WoW` and `/sdcard/wow`, each as `<dir>/Data` or the MPQs directly in `<dir>`.

## When it doesn't work

| symptom | look at |
|---|---|
| "No WebGPU in this WebView" | update *Android System WebView* (Play Store), or tap *Open in Chrome* and leave the app running behind it: it is the server |
| black screen, no dialog | `chrome://inspect` on a PC with USB debugging on: the WebView is debuggable, its console is the game's |
| "Could not start the game server" | the screen shows the tail of `host.log`; usually the Data folder is incomplete |
| "This Data folder is from a later World of Warcraft client" | the folder is a Burning Crusade / Wrath (or later) client; its art and tables are laid out differently (race portraits come out doubled, no hero appears). Use the 1.12.1 client (build 5875) |
| controller does nothing | the app forwards the controller itself, so check the handheld isn't in a key-mapping mode that turns buttons into touches |
| `adb logcat -s survivors` | the boot steps and the page's console lines |

The debug key lives in `~/.android/debug.keystore` on the machine that built the APK. An APK
built on another machine has a different key, so `adb install -r` over it fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: uninstall the old one first (that also deletes the app's
own data folder, so a `Data` copied there has to be pushed again; `/sdcard/wow-data` survives).
