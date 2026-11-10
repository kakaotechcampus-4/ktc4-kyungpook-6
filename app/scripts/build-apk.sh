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

# SDK 를 찾는 순서: 환경변수 → 이미 있는 local.properties → 흔히 쓰는 자리.
# local.properties 가 이미 맞게 돼 있으면 건드리지 않는다 — ndk.dir 같은 다른 줄이 날아간다.
if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -f android/local.properties ]; then
  ANDROID_HOME=$(sed -n 's/^sdk\.dir=//p' android/local.properties | tail -1)
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/local/share/android-sdk; do
    [ -d "$candidate" ] && { ANDROID_HOME="$candidate"; break; }
  done
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || {
  echo "안드로이드 SDK 를 못 찾았습니다. ANDROID_HOME 을 넣고 다시 돌리세요." >&2
  exit 1
}
export ANDROID_HOME

# Gradle 이 SDK 를 찾는 길. 이미 같은 값이면 쓰지 않는다. (git 에는 안 올라간다)
if ! grep -qxF "sdk.dir=$ANDROID_HOME" android/local.properties 2>/dev/null; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
fi

mkdir -p "$OUT_DIR"

# 아동 홈 지도는 이 키로 카카오 지도를 부른다(src/pages/ChildHomePage.tsx).
# 빌드 인자가 아니라 app/.env 에서만 온다 — 없으면 지도만 빈 채로 나오고 빌드는 성공한다.
if [ -z "${VITE_KAKAO_JS_KEY:-}" ] && ! grep -q '^VITE_KAKAO_JS_KEY=.' .env 2>/dev/null; then
  echo "⚠ VITE_KAKAO_JS_KEY 가 없습니다 — 아동 홈 지도가 빈 채로 나옵니다 (app/.env 에 넣으세요)"
fi

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

  # Gradle 은 플레이버 이름을 선언한 대로(소문자) 쓴다. 대문자로 찾으면 맥(대소문자 무시)에서만
  # 되고 리눅스·CI 에서는 빌드를 다 돌린 뒤 cp 에서 죽는다.
  local dir="${role}"
  dir=$(printf '%s' "$dir" | tr '[:upper:]' '[:lower:]')
  local apk="android/app/build/outputs/apk/${dir}/debug/app-${dir}-debug.apk"
  cp "$apk" "$OUT_DIR/선한레이더-${label}.apk"
}

build_one CHILD Child 아동
build_one OWNER Owner 점주

echo
echo "만든 APK:"
ls -lh "$OUT_DIR"/*.apk | awk '{print "  " $9 "  " $5}'
echo
echo "폰에 깔기:  adb install -r '<위 경로>'"
