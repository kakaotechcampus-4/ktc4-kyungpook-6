#!/usr/bin/env bash
#
# 점주 앱·아동 앱 APK 를 만든다.
#
#   ./scripts/build-apk.sh http://192.168.0.10:8080
#
# 인자는 앱이 바라볼 백엔드 주소다. 폰에서 보는 주소라 localhost 는 안 된다 —
# 노트북의 LAN 주소를 적는다(`ipconfig getifaddr en0`).
# 안 적으면 .env 의 VITE_BACKEND_URL 을 쓴다.
#
# 역할마다 웹을 다시 빌드해야 한다. 시작 화면이 빌드 때 박히고(VITE_APP_ROLE),
# cap sync 는 android/app/src/main/assets/public 한 곳에만 쓰기 때문이다.
# 그래서 한 역할씩 build → sync → assemble 을 돈다.
#
# 결과는 debug 서명 APK 다. 폰에 바로 깔리고 시연에 쓸 수 있다.
# 스토어에 올리는 release 서명은 키스토어가 필요해서 여기서 하지 않는다.
set -euo pipefail

cd "$(dirname "$0")/.."
BACKEND_URL="${1:-}"
OUT_DIR="$PWD/build-apk"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export ANDROID_HOME
[ -d "$ANDROID_HOME" ] || { echo "안드로이드 SDK 가 없습니다: $ANDROID_HOME" >&2; exit 1; }

# Gradle 이 SDK 를 찾는 길. local.properties 는 git 에 올라가지 않는다.
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties

mkdir -p "$OUT_DIR"

build_one() {
  local role="$1" flavor="$2" label="$3"

  echo
  echo "=== $label ($role) ==="
  if [ -n "$BACKEND_URL" ]; then
    VITE_APP_ROLE="$role" VITE_BACKEND_URL="$BACKEND_URL" npm run build
  else
    VITE_APP_ROLE="$role" npm run build
  fi
  npx cap sync android
  (cd android && ./gradlew "assemble${flavor}Debug")

  local apk="android/app/build/outputs/apk/${role}/debug/app-${role}-debug.apk"
  cp "$apk" "$OUT_DIR/선한레이더-${label}.apk"
}

build_one CHILD Child 아동
build_one OWNER Owner 점주

echo
echo "만든 APK:"
ls -lh "$OUT_DIR"/*.apk | awk '{print "  " $9 "  " $5}'
echo
echo "폰에 깔기:  adb install -r '<위 경로>'"
