#!/usr/bin/env bash
# 운영 서버(EC2) 안에서 실행되는 배포 앞단계입니다. SSM 이 이 파일을 부릅니다
# (.github/workflows/deploy-main.yml). 배포 자체는 scripts/deploy.sh 가 합니다.
#
# 왜 따로 두는가 — SSM `send-command` 의 명령 문자열은 **명령 기록에 그대로 남습니다**
# (SSM 콘솔 Command history · CloudTrail · Actions 로그). 그래서
#   ① 비밀값을 명령에 실을 수 없습니다 — 평문으로 쌓입니다
#   ② 긴 로직을 JSON 배열에 밀어 넣으면 리뷰할 수 없습니다
# 명령에는 **파라미터 이름만** 남기고, 값은 서버가 직접 받아 갑니다.
#
# AI 조사 자격증명이 없으면 **그대로 넘어갑니다** — 지금과 같이 조사만 503 이고 화면은 정상입니다
# (ai/src/server/main.py:130). 배포를 멈추지 않습니다.
set -euo pipefail

REGION=ap-northeast-2
PARAM_SA=/ktc4/prod/GCP_SA_KEY          # SecureString — 서비스 계정 JSON
PARAM_PROJECT=/ktc4/prod/GCP_PROJECT_ID # String — 비밀이 아니라 보이는 편이 낫습니다

# 🚨 `$HOME` 을 쓰지 않습니다. SSM 은 이 스크립트를 **root 로, HOME 이 설정되지 않은 셸에서**
#    돌립니다(2026-10-16 실측: whoami=root · HOME 없음 · `~` 확장 안 됨). `set -u` 와 겹쳐
#    `$HOME/...` 한 줄에서 스크립트가 죽고 배포 전체가 실패합니다.
#    레포 밖이면서 배포 사용자 홈인 이 경로로 고정하고, 필요하면 환경변수로 바꿉니다.
KEY_DIR="${KTC4_KEY_DIR:-/home/ubuntu/.ktc4}"
KEY_FILE="$KEY_DIR/vertex.json"

# 서버에 aws CLI 가 없을 수 있습니다. 그때는 컨테이너로 부르는데 `--network host` 가 필요합니다.
# 인스턴스 메타데이터(IMDS)의 HttpPutResponseHopLimit 이 1 이라, 브리지 네트워크 컨테이너는
# 자격증명을 받지 못합니다. host 네트워크면 호스트와 같은 경로라 받습니다.
if command -v aws >/dev/null 2>&1; then
  awscli() { aws "$@"; }
else
  awscli() { docker run --rm --network host amazon/aws-cli:latest "$@"; }
fi

fetch_credentials() {
  local tmp
  tmp=$(mktemp)
  if ! awscli ssm get-parameter --region "$REGION" --name "$PARAM_SA" \
        --with-decryption --query Parameter.Value --output text > "$tmp" 2>/dev/null; then
    unlink "$tmp" || true
    echo "[prod] $PARAM_SA 를 읽지 못했습니다 — AI 조사는 503 으로 돕니다(화면은 정상)"
    return 1
  fi

  # 깨진 파일을 넘기면 조사가 503 이 아니라 더 이상한 실패로 바뀔 수 있습니다. 먼저 JSON 인지 봅니다.
  if ! python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('[prod] 서비스 계정:', d['client_email'])" "$tmp"; then
    unlink "$tmp" || true
    echo "[prod] 받은 값이 서비스 계정 JSON 이 아닙니다 — 붙이지 않고 넘어갑니다"
    return 1
  fi

  mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
  mv "$tmp" "$KEY_FILE" && chmod 600 "$KEY_FILE"
  return 0
}

if fetch_credentials; then
  # compose 는 셸 환경변수를 env 파일보다 먼저 읽습니다. 그래서 서버 .env 를 고치지 않아도 이 값이
  # 이깁니다 — prod 는 deploy.sh 가 --env-file 을 아예 넘기지 않아 더 단순합니다.
  export VERTEX_KEY_PATH="$KEY_FILE"
  if PROJECT=$(awscli ssm get-parameter --region "$REGION" --name "$PARAM_PROJECT" \
                 --query Parameter.Value --output text 2>/dev/null) && [ -n "$PROJECT" ]; then
    export GOOGLE_CLOUD_PROJECT="$PROJECT"
  else
    echo "[prod] $PARAM_PROJECT 가 없습니다 — 프로젝트 ID 없이는 조사가 503 입니다"
  fi
fi

exec ./scripts/deploy.sh prod up -d --build --remove-orphans
