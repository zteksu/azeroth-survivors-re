# Android (sideload APK)

A private, personal build of the browser version for one Android device (written for an AYN Odin 3,
should work on any arm64 phone or handheld with a WebGPU-capable GPU). It is a **wrapper, not a
port**: the game is the same `web/dist` bundle `make start` serves, running in a WebView.

What the APK holds:

- `libwenillahost.so`: `wenilla-host` cross-compiled for `aarch64-linux-android` (an executable,
  named like a library so Android installs it where it may be run).
- `assets/www/`: the `web/dist` bundle (the wasm and its `.br` sibling, the page's JS).

What it does on launch: unpacks `www/` to app storage (first launch and after each update),
starts the host on `127.0.0.1:8090` against the game data on the device, then loads
`http://127.0.0.1:8090/` full-screen and landscape. Back / controller B is sent to the game as Esc.

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
# Either: the app's own folder (no permission needed)
adb push wow-data/Data /sdcard/Android/data/survivors.android.local/files/
# Or: a plain folder you can also fill with a file manager; the app asks for "All files access"
adb push wow-data/Data /sdcard/wow-data/
```

Without adb, put `Data` at `/sdcard/wow-data/Data` with a file manager and allow "All files
access" when the app asks. The app looks in, in order: its own folder, `/sdcard/wow-data`,
`/sdcard/WoW`, `/sdcard/wow`, each as `<dir>/Data` or the MPQs directly in `<dir>`.

## When it doesn't work

| symptom | look at |
|---|---|
| "No WebGPU in this WebView" | update *Android System WebView* (Play Store), or tap *Open in Chrome* and leave the app running behind it: it is the server |
| black screen, no dialog | `chrome://inspect` on a PC with USB debugging on: the WebView is debuggable, its console is the game's |
| "Could not start the game server" | the screen shows the tail of `host.log`; usually the Data folder is incomplete |
| controller does nothing in the WebView but works in Chrome | the WebView's Gamepad support differs by version; use *Open in Chrome*, or Odin's key mapping |
| `adb logcat -s survivors` | the boot steps and the page's console lines |

The debug key lives in `~/.android/debug.keystore` on the machine that built the APK. An APK
built on another machine has a different key, so `adb install -r` over it fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: uninstall the old one first (that also deletes the app's
own data folder, so a `Data` copied there has to be pushed again; `/sdcard/wow-data` survives).
