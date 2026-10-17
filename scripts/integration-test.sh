#!/usr/bin/env bash
# 배포 환경에서 관리자 프로세스를 한 바퀴 돌려 보고, 어디서 막히는지와 걸린 시간을 찍습니다.
# 쓰는 법과 배경은 docs/배포환경에서_작업하는_방법.md 에 있습니다.
#
# 사용법:
#   TOKEN=<관리자 토큰> scripts/integration-test.sh [주입할 JSON 파일]
#
#   API 주소는 API 환경변수로 바꿉니다(기본값은 dev).
#   JSON 을 주지 않으면 주입 단계를 건너뛰고 이미 들어 있는 자료로 조사만 돌립니다.
#
# 🚨 비밀번호는 이 스크립트에 적지 않습니다. 토큰은 사람이 먼저 받아서 넘깁니다.
#      read -s -p '관리자 비밀번호: ' PW; echo
#      TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
#                -d "{\"email\":\"<관리자 이메일>\",\"password\":\"$PW\"}" \
#              | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
#      unset PW
#
# 읽기와 "넣기"만 합니다 — 지우는 호출은 없습니다. 다만 조사를 실제로 만들고,
# 주입 JSON 을 주면 가게가 실제로 들어갑니다. 운영(prod)에는 쓰지 마세요.
set -uo pipefail

API="${API:-https://goodradar-api.mojan.kr}"
IMPORT_JSON="${1:-}"
START=$(date +%s)

if [[ -z "${TOKEN:-}" ]]; then
  echo "TOKEN 이 없습니다. 위 주석대로 관리자 토큰을 먼저 받으세요." >&2
  exit 1
fi

AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
step() { printf '\n── %s\n' "$1"; }
elapsed() { printf '   (여기까지 %s초)\n' "$(( $(date +%s) - START ))"; }

step "0. 배포본이 살아 있는가 · 내 코드가 올라갔는가"
curl -s -o /dev/null -w "   /ping  %{http_code}  %{time_total}초\n" "$API/ping"
curl -s "$API/v3/api-docs" | python3 -c "
import sys, json
paths = json.load(sys.stdin)['paths']
n = sum(1 for p in paths for m in paths[p] if m in ('get','post','put','patch','delete'))
print(f'   엔드포인트 {n}개')
print('   주입 API:', '있음' if '/api/admin/stores/imports' in paths else '없음 — 아직 배포 전입니다')
"

step "1. 내가 관리자인가"
curl -s "${AUTH[@]}" "$API/api/auth/me"; echo

if [[ -n "$IMPORT_JSON" ]]; then
  [[ -f "$IMPORT_JSON" ]] || { echo "   파일이 없습니다: $IMPORT_JSON" >&2; exit 1; }

  step "2. 가게 자료 주입"
  curl -s -X POST "$API/api/admin/stores/imports" "${AUTH[@]}" --data-binary @"$IMPORT_JSON" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"   요청 {d['requested']} · 넣음 {d['inserted']} · 건너뜀 {d['skipped']} · 거절 {d['rejected']}\");[print('   거절:',r) for r in d['rejections']]"
  elapsed

  step "3. 같은 요청을 한 번 더 — 두 배로 늘지 않아야 한다"
  curl -s -X POST "$API/api/admin/stores/imports" "${AUTH[@]}" --data-binary @"$IMPORT_JSON" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"   넣음 {d['inserted']} · 건너뜀 {d['skipped']}\")"
else
  step "2~3. 주입 건너뜀 (JSON 을 주지 않았습니다)"
fi

step "4. 국세청 대조 상태"
curl -s "${AUTH[@]}" "$API/api/stores/nts-checks?page=0&limit=100" | python3 -c "
import sys, json, collections
d = json.load(sys.stdin); rows = d['content']
print('   총', d['totalElements'], '곳 (아래는 첫 100곳 기준)')
for key in ('internalStatus','ntsLookup','ntsStatus','statusComparison','dataProblem'):
    print(f'   {key:17}', dict(collections.Counter(r.get(key) for r in rows)))
"

# 조사 대상이 될 가게를 고른다. /api/stores 의 앞 5곳을 그냥 집으면, 국세청과 대조할 수 없는
# 가게(NOT_COMPARABLE)만 걸려서 백엔드가 400 no-investigation-target 으로 거절한다.
# 그래서 국세청 대조가 끝난 가게 중 비교 결과가 있는 것부터 고른다.
step "5. 조사 시작 (대조 결과가 있는 앞 5곳)"
IDS=$(curl -s "${AUTH[@]}" "$API/api/stores/nts-checks?page=0&limit=100" | python3 -c "
import sys, json
rows = json.load(sys.stdin)['content']
pick = [r for r in rows if r.get('statusComparison') not in (None, 'NOT_COMPARABLE')]
print(','.join(str(r['storeId']) for r in pick[:5]))
")
if [[ -z "$IDS" ]]; then
  echo "   조사 대상이 될 가게가 없습니다 — 국세청 대조가 끝난 가게가 있어야 합니다."
  echo "   주입 JSON 에 ntsCheck 를 함께 넣으면 대조 기록까지 만들어집니다(4번 참고)."
  exit 1
fi
echo "   storeIds=[$IDS]"
JOB=$(curl -s -X POST "$API/api/jobs" "${AUTH[@]}" -d "{\"storeIds\":[$IDS]}")
echo "   $JOB"
JOB_ID=$(printf '%s' "$JOB" | python3 -c "import sys,json;print(json.load(sys.stdin).get('jobId',''))" 2>/dev/null)
[[ -z "$JOB_ID" ]] && { echo "   조사를 시작하지 못했습니다 — 위 응답을 보세요(진행 중인 조사가 있으면 409)."; exit 1; }
elapsed

step "6. 끝날 때까지 기다리기"
python3 - "$API" "$JOB_ID" "$TOKEN" <<'PY'
import json, sys, time, urllib.request
api, job_id, token = sys.argv[1], sys.argv[2], sys.argv[3]
started = time.time()
data = None
for _ in range(120):
    req = urllib.request.Request(f"{api}/api/jobs/{job_id}", headers={"Authorization": "Bearer " + token})
    data = json.load(urllib.request.urlopen(req))
    print(f"   {data['status']} · {data['completedCount']}/{data['targetCount']}곳 · {int(time.time() - started)}초", flush=True)
    if data["status"] not in ("PENDING", "IN_PROGRESS"):
        break
    time.sleep(3)
tasks = data.get("tasks", [])
ok = sum(1 for t in tasks if not t.get("failureReason"))
print(f"   끝 — 성공 {ok}곳 / 실패 {len(tasks) - ok}곳")
for t in tasks:
    if t.get("failureReason"):
        print(f"     - {t['storeName']}: 실패 — {t['failureReason']}")
    else:
        changes = ", ".join(f"{c['field']}={c['value']}" for c in (t.get("proposedChanges") or [])) or "변화 없음"
        print(f"     - {t['storeName']}: {t.get('classification')} · {changes}")
PY
elapsed

step "7. 화면에서 사람이 볼 것"
cat <<'TXT'
   https://goodradar.mojan.kr/
   - 로그인 → 가게 목록에 주입한 가게가 보이는가
   - 가게를 골라 "조사 시작하기" → 결과 화면으로 넘어가고 진행 상황이 올라가는가
   - 결과 카드의 "수정 사항" 에 바뀔 값이 보이는가 (예: 영업 상태: 폐업)
   - "즉시 수정 반영하기" → 가게 값이 바뀌고 "확인 완료" 로 바뀌는가
TXT

printf '\n한 바퀴 총 %s초\n' "$(( $(date +%s) - START ))"
