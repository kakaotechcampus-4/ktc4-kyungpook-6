#!/usr/bin/env bash
#
# 점주 앱·아동 앱을 **웹으로** 만든다. 같은 도메인의 하위 경로에 올리는 것을 전제로 한다.
#
#   ./scripts/build-web.sh                 /app/child · /app/owner 로 만든다
#   ./scripts/build-web.sh https://example.com   백엔드가 다른 도메인일 때만 적는다
#
# 인자를 비워 두면 백엔드 주소도 비운 채로 빌드한다. 그러면 앱이 /api/... 를 **상대 경로**로
# 부르고, 같은 도메인에서 서비스되므로 CORS 가 아예 필요 없다. 다른 도메인에 올릴 때만
# 주소를 적고, 그때는 백엔드 CORS 에 그 출처를 넣어야 한다.
#
# 결과는 app/build-web/{child,owner} 다. APK 는 scripts/build-apk.sh 가 따로 만든다.
set -euo pipefail

cd "$(dirname "$0")/.."
BACKEND_URL="${1:-}"
OUT_DIR="$PWD/build-web"
BASE_PREFIX="/app"

# 아동 홈 지도는 이 키로 카카오 지도를 부른다. 빌드 인자가 아니라 app/.env 에서 온다.
if [ -z "${VITE_KAKAO_JS_KEY:-}" ] && ! grep -q '^VITE_KAKAO_JS_KEY=.' .env 2>/dev/null; then
  echo "⚠ VITE_KAKAO_JS_KEY 가 없습니다 — 아동 홈 지도가 빈 채로 나옵니다 (app/.env 에 넣으세요)"
fi

build_one() {
  local role="$1" dir="$2" label="$3"

  echo
  echo "=== $label ($BASE_PREFIX/$dir/) ==="
  VITE_APP_ROLE="$role" \
  VITE_BASE_PATH="$BASE_PREFIX/$dir/" \
  VITE_BACKEND_URL="$BACKEND_URL" \
    npm run build

  mkdir -p "$OUT_DIR/$dir"
  # 지난 빌드가 남아 있으면 쓰이지 않는 조각이 쌓인다. 폴더째 새로 만든다.
  /usr/bin/trash "$OUT_DIR/$dir" 2>/dev/null || true
  mkdir -p "$OUT_DIR/$dir"
  cp -R dist/. "$OUT_DIR/$dir/"
}

build_one CHILD child 아동
build_one OWNER owner 점주

echo
echo "만든 것:"
du -sh "$OUT_DIR"/* | sed 's|^|  |'
echo
echo "올릴 자리:  <도메인>$BASE_PREFIX/child/  ·  <도메인>$BASE_PREFIX/owner/"
