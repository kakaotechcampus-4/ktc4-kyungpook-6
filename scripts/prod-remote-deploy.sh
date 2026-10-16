#!/usr/bin/env bash
# 운영 서버(EC2) 안에서 실행되는 배포 앞단계입니다. SSM 이 이 파일을 부릅니다
# (.github/workflows/deploy-main.yml). 배포 자체는 scripts/deploy.sh 가 합니다.
#
# 하는 일은 하나입니다 — **SSM 파라미터 스토어의 /ktc4/prod/ 아래 값을 전부 읽어
# 환경변수로 넘기고** deploy.sh 를 부릅니다.
#
# 왜 이렇게 하는가
#   ① **비밀값을 SSM 명령에 실을 수 없습니다.** send-command 의 파라미터는 SSM 콘솔
#      Command history · CloudTrail · Actions 로그에 **그대로 남습니다.** 그래서 dev
#      (deploy-dev.yml)처럼 워크플로 `env:` 로 넘기는 수법을 prod 에는 쓸 수 없습니다.
#      값은 서버가 직접 받아 가고, 명령에는 **이름만** 남습니다.
#   ② 서버 `.env` 를 사람이 손으로 고치지 않아도 됩니다. compose 는 셸 환경변수를 env 파일보다
#      먼저 읽고, prod 는 deploy.sh 가 `--env-file` 을 아예 넘기지 않아 더 단순합니다.
#   ③ 긴 셸을 JSON 배열에 밀어 넣으면 아무도 리뷰할 수 없습니다.
#
# 값이 없으면 **그대로 넘어갑니다** — 배포를 멈추지 않습니다. 대신 무엇이 비어서 무엇이 꺼지는지
# 로그에 크게 찍습니다. 조용히 비는 것이 이 프로젝트에서 반복된 사고의 원인이었습니다.
#
# 파라미터 추가하는 법 (코드 수정 없이 늘어납니다)
#   aws ssm put-parameter --name /ktc4/prod/<환경변수이름> --type SecureString --value ...
#   이름을 그대로 환경변수 이름으로 씁니다. 예외는 아래 둘입니다.
#     GCP_SA_KEY     → 파일로 떨어뜨려 VERTEX_KEY_PATH 로 넘깁니다(환경변수로 싣지 않습니다)
#     GCP_PROJECT_ID → GOOGLE_CLOUD_PROJECT 로 이름을 바꿔 넘깁니다
set -euo pipefail

REGION=ap-northeast-2
PARAM_PATH=/ktc4/prod/

# 🚨 `$HOME` 을 쓰지 않습니다. SSM 은 이 스크립트를 **root 로, HOME 이 설정되지 않은 셸에서**
#    돌립니다(2026-10-16 실측: whoami=root · HOME 없음 · `~` 확장 안 됨). `set -u` 와 겹쳐
#    `$HOME/...` 한 줄에서 스크립트가 죽고 **배포 전체가 실패**합니다.
KEY_DIR="${KTC4_KEY_DIR:-/home/ubuntu/.ktc4}"
KEY_FILE="$KEY_DIR/vertex.json"

# 서버에 aws CLI 가 없을 수 있습니다(2026-10-16 현재 없습니다). 그때는 컨테이너로 부르는데
# `--network host` 가 필요합니다 — 인스턴스 메타데이터의 HttpPutResponseHopLimit 이 1 이라
# 브리지 네트워크 컨테이너는 자격증명을 받지 못합니다.
if command -v aws >/dev/null 2>&1; then
  awscli() { aws "$@"; }
else
  awscli() { docker run --rm --network host amazon/aws-cli:latest "$@"; }
fi

WORK=$(mktemp -d)
cleanup() { [ -n "${WORK:-}" ] && [ -d "$WORK" ] && find "$WORK" -mindepth 1 -delete 2>/dev/null; rmdir "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

if ! awscli ssm get-parameters-by-path --region "$REGION" --path "$PARAM_PATH" \
      --recursive --with-decryption --output json > "$WORK/params.json" 2>"$WORK/err"; then
  echo "[prod] 파라미터를 읽지 못했습니다 — 저장된 값 없이 배포합니다"
  echo "[prod] $(head -2 "$WORK/err")"
  printf '{"Parameters":[]}' > "$WORK/params.json"
fi

# 이름을 환경변수로 바꿔 쓸 수 있게 셸 파일로 떨어뜨립니다. 값에 따옴표·줄바꿈이 있어도
# 안전하도록 python 의 shlex 로 따옴표를 붙입니다(eval 로 값을 해석하지 않습니다).
python3 - "$WORK/params.json" "$WORK/env.sh" "$WORK/sa.json" <<'PY'
import json, shlex, sys

src, env_out, sa_out = sys.argv[1], sys.argv[2], sys.argv[3]
params = json.load(open(src)).get("Parameters", [])

RENAME = {"GCP_PROJECT_ID": "GOOGLE_CLOUD_PROJECT"}
lines, loaded = [], []
for p in params:
    name = p["Name"].rsplit("/", 1)[-1]
    value = p.get("Value", "")
    if name == "GCP_SA_KEY":                       # 환경변수가 아니라 파일로 간다
        open(sa_out, "w").write(value)
        loaded.append("GCP_SA_KEY(파일)")
        continue
    key = RENAME.get(name, name)
    lines.append("export %s=%s\n" % (key, shlex.quote(value)))
    loaded.append("%s(%d자)" % (key, len(value)))

open(env_out, "w").writelines(lines)
print("[prod] 파라미터 %d개: %s" % (len(params), " ".join(loaded) or "없음"))
PY

# shellcheck source=/dev/null
[ -s "$WORK/env.sh" ] && . "$WORK/env.sh"

# 서비스 계정 JSON 은 레포 밖에 둡니다 — git reset 과 무관하게 남고, 실수로 커밋될 수 없습니다.
if [ -s "$WORK/sa.json" ]; then
  if python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('[prod] 서비스 계정:', d['client_email'])" "$WORK/sa.json"; then
    mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
    cp "$WORK/sa.json" "$KEY_FILE" && chmod 600 "$KEY_FILE"
    export VERTEX_KEY_PATH="$KEY_FILE"
  else
    echo "[prod] 받은 값이 서비스 계정 JSON 이 아닙니다 — 붙이지 않고 넘어갑니다"
  fi
fi

# 무엇이 비어서 무엇이 꺼지는지 크게 찍습니다. 조용히 비는 것이 반복된 사고의 원인이었습니다.
# `return 0` 이 꼭 필요하다. `[ -z … ] && echo` 만 쓰면 값이 **채워져 있을 때** 함수가 1 을
# 돌려주고, `set -e` 가 거기서 스크립트를 죽인다 — 설정이 올바를 때만 배포가 안 되는,
# 가장 찾기 어려운 모양의 실패가 된다(2026-10-16 에 실제로 그렇게 죽는 것을 보고 고쳤다).
warn_missing() {
  local var="$1" effect="$2"
  if [ -z "${!var:-}" ]; then
    echo "[prod] ⚠️  $var 없음 — $effect"
  fi
  return 0
}
warn_missing JWT_SECRET            "기동마다 임의 서명 키를 만듭니다. 재시작하면 발급한 토큰이 전부 무효입니다"
warn_missing ADMIN_EMAIL           "관리자 계정이 만들어지지 않습니다(승인·자료 주입·조사 시작 불가)"
warn_missing ADMIN_PASSWORD        "같음"
warn_missing INTERNAL_API_KEY      "서버 간 키 인증이 꺼집니다 — AI 가 백엔드 조회 API 를 부를 수 없습니다"
warn_missing NTS_SERVICE_KEY       "국세청 대조를 할 수 없습니다"
warn_missing CORS_ALLOWED_ORIGINS  "base 기본값(localhost)만 허용돼 공개 주소의 화면이 API 를 부를 수 없습니다"
warn_missing GOOGLE_CLOUD_PROJECT  "AI 조사가 503 입니다(화면은 정상)"
warn_missing VERTEX_KEY_PATH       "AI 조사가 503 입니다(화면은 정상)"

exec ./scripts/deploy.sh prod up -d --build --remove-orphans
