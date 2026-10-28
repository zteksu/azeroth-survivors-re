#!/usr/bin/env bash
#
# android-build.sh - package the browser build as a sideloadable Android APK (arm64).
#
#   scripts/android-build.sh              # → android/azeroth-survivors.apk
#
# The APK is a wrapper, not a port: it carries wenilla-host cross-compiled for Android and the
# web/dist bundle, starts the host on 127.0.0.1:8090 against game data on the device, and shows
# the page full-screen in a WebView. The game data is never in the APK; see android/README.md.
#
# Prerequisites: the wasm build (scripts/web-build.sh → web/dist), an Android SDK with an NDK
# (ANDROID_HOME, or ANDROID_SDK_ROOT; ANDROID_NDK_HOME picks the NDK, else the newest under the
# SDK), JDK 17+, and `rustup target add aarch64-linux-android`.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

API=29   # the app's minSdk
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "${SDK}" ] && [ -d "${SDK}" ] || { echo "set ANDROID_HOME to your Android SDK" >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-$(ls -d "${SDK}"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "${NDK}" ] || { echo "no NDK under ${SDK}/ndk — sdkmanager 'ndk;27.2.12479018'" >&2; exit 1; }
[ -f web/dist/wenilla_bg.wasm ] || { echo "web/dist is empty — run scripts/web-build.sh first" >&2; exit 1; }
rustup target list --installed | grep -qx aarch64-linux-android || rustup target add aarch64-linux-android

HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
CLANG="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin/aarch64-linux-android${API}-clang"
[ -x "${CLANG}" ] || { echo "no NDK clang at ${CLANG}" >&2; exit 1; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${CLANG}"
export CC_aarch64_linux_android="${CLANG}"
export AR_aarch64_linux_android="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin/llvm-ar"
cargo build --release --target aarch64-linux-android -p wenilla-host

# jniLibs is the one place an APK's executables land with exec permission (nativeLibraryDir);
# the lib*.so name is what gets it extracted there.
JNI=android/app/src/main/jniLibs/arm64-v8a
mkdir -p "${JNI}"
cp target/aarch64-linux-android/release/wenilla-host "${JNI}/libwenillahost.so"
"${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin/llvm-strip" "${JNI}/libwenillahost.so"

# The page bundle. No .gz siblings: AGP reads `foo.gz` as a packed `foo` and fails on the
# duplicate, and over loopback the precompressed copies save nothing anyway.
WWW=android/app/src/main/assets/www
rm -rf "${WWW}" && mkdir -p "${WWW}"
cp -R web/dist/. "${WWW}/"
find "${WWW}" -name '*.gz' -delete
# The APK's overlay (controller mapping, touch controls, the side menu): added to this copy of the
# dev page only, never to web/ itself.
cp android/web/android.js android/web/android.css "${WWW}/"
sed -i.bak 's|<link rel="icon"[^>]*>|&\n<link rel="stylesheet" href="android.css">\n<script src="android.js" defer></script>|' "${WWW}/index.html"
rm -f "${WWW}/index.html.bak"
grep -q 'src="android.js"' "${WWW}/index.html" || { echo "could not add android.js to index.html" >&2; exit 1; }

(cd android && ./gradlew --no-daemon assembleRelease)
cp android/app/build/outputs/apk/release/app-release.apk android/azeroth-survivors.apk
ls -la android/azeroth-survivors.apk
echo "install: adb install -r android/azeroth-survivors.apk"
