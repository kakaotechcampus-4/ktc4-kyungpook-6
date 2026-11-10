# 가게 정보 전화 확인 API (EP06-E)

이 프로젝트는 **가게 정보 관리 시스템에서 전화 확인을 담당하는 독립 API 서버**입니다. 관리 시스템이 가게의 등록 정보와 확인할 항목을 보내면, 서버가 EP06-E 모뎀으로 가게에 전화를 걸어 관계자에게 질문하고 확인된 값을 돌려줍니다. 통화는 비동기로 처리하며, 관리 시스템은 발급된 `verificationId`로 결과를 조회합니다.

이 서버는 확인 결과를 SQLite에 보관하지만 **관리 시스템의 가게 정보를 직접 수정하지 않습니다.** 결과의 `answers`를 기존 정보에 반영할지, 어떤 기준으로 반영할지는 호출하는 시스템이 결정해야 합니다. 실제 발신이 일어나는 API이므로 운영 전화번호로 요청하기 전에 대상과 질문을 확인하세요.

```text
관리 시스템 ── POST (대상 가게·질문) ──▶ API 접수 큐 ──▶ EP06-E 발신
관리 시스템 ◀─ 202 + verificationId ── API
관리 시스템 ── GET (verificationId) ──▶ API 결과 저장소
관리 시스템 ◀─ 상태·확인된 답변 ──────── API
```

질문은 `VALUE_CONFIRMATION`(예상하는 값이 맞는지 확인)과 `CHANGE_DETECTION`(현재 등록된 값이 그대로인지 확인) 두 종류입니다. 통화 중 먼저 가게 관계자인지 확인하고, 질문별로 답변을 수집합니다. 확인되지 않은 항목은 추측해 채우지 않으며, 실패한 통화에서도 이미 확인한 답변은 조회할 수 있습니다.

기존 `ep06_wordchain`의 모뎀 AT 제어, USB NMEA PCM 송수신, 발화 감지, 오디오 변환 코드를 바탕으로 구현했습니다. Python 3.11 이상과 EP06-E가 연결된 Linux 환경을 대상으로 합니다.

## 실행

1. `.env.example`을 `.env`로 복사합니다.
2. `VERIFICATION_API_KEY`에는 이 HTTP 서버의 접근 키를 지정합니다. `STT_API_KEY`, `LLM_API_KEY`, `ELEVENLABS_API_KEY`, `JEV_API_KEY`에는 각 서비스 키를 입력합니다. 예시 파일에 실제 키는 없습니다.
3. `ELEVENLABS_VOICE_ID`에는 ElevenLabs 계정에서 사용할 음성의 ID를 넣습니다. `STT_URL`, `LLM_BASE_URL`, `JEV_URL`에는 발급받은 게이트웨이 주소를 설정합니다. 다른 음성을 사용하려면 음성 ID를 변경하고 서버를 재시작합니다.
4. `AT_PORT`, `VOICE_PORT`를 실제 장치에 맞춥니다. 예시는 `/dev/ttyUSB2`, `/dev/ttyUSB1`입니다. 백엔드 포트를 변경하려면 `BACKEND_PORT`를 설정합니다(기본값 `8000`).
5. 서버를 실행합니다.

```bash
cp .env.example .env
# .env 편집 후
docker compose up --build
```

서버 시작만으로 새 전화를 생성하지 않습니다. **POST 요청을 접수하면 실제 발신 큐에 들어갑니다.** 이전 실행에서 남아 있던 미처리 PENDING 요청은 재시작 시 이어서 처리합니다. 기본 포트 바인딩은 `127.0.0.1:8000`입니다. `BACKEND_PORT=9000`으로 설정하면 호스트와 컨테이너 모두 9000 포트를 사용하며, API 문서는 `http://localhost:9000/docs`에서 확인할 수 있습니다.

Nginx Proxy Manager(NPM)가 별도 Docker 컨테이너에서 호스트의 포트로 접속한다면 `.env`에 `BACKEND_PORT=8020`, `BACKEND_BIND_HOST=0.0.0.0`을 설정하고 `docker compose up -d --build`로 재생성합니다. NPM의 Forward Scheme은 `http`, Forward Hostname/IP는 백엔드 호스트의 컨테이너에서 접근 가능한 IP, Forward Port는 `8020`으로 설정합니다. NPM 컨테이너의 `127.0.0.1`은 백엔드 호스트가 아니라 NPM 컨테이너 자신을 가리킵니다. 8020 포트는 필요한 프록시에서만 접근할 수 있도록 방화벽을 설정합니다. NPM이 호스트에서 직접 실행 중이면 기본 `BACKEND_BIND_HOST=127.0.0.1`을 유지할 수 있습니다. `/healthz`는 인증 없이 연결 상태를 확인하는 경로입니다.

호스트에서 직접 실행하려면:

```bash
sudo apt-get install -y ffmpeg
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
set -a
. ./.env
set +a
./cache-tts.sh
uvicorn verification.api:app --host 127.0.0.1 --port "${BACKEND_PORT:-8000}" --workers 1
```

### 공용 TTS 캐시 미리 생성

`agent.json`에서 변수가 없는 템플릿 문구와 모든 변형을 찾아 `data/tts-static/`에 MP3와 통화용 8kHz WAV를 생성합니다. 같은 문구가 여러 템플릿에 있어도 한 번만 생성합니다. 실행 결과에 전체 고유 문구 수, 새 TTS 합성 수, WAV 생성 수, 재사용 수를 표시합니다. `.env`의 `ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID`, `TTS_MODEL`, `DATA_DIR`, `PERSONA_FILE` 설정을 사용하며 `ffmpeg`가 필요합니다.

```bash
./cache-tts.sh               # 이미 완성된 캐시는 재사용
./cache-tts.sh --force       # 모든 문구의 MP3와 WAV를 다시 생성
./cache-tts.sh --keep-stale  # 쓰이지 않는 캐시 파일을 지우지 않고 남겨 둠
```

**컨테이너는 서버를 띄우기 전에 이 작업을 자동으로 수행합니다.** 캐시가 비어 있으면 첫 통화 중에 합성이 일어나 응답이 늦어지므로, 그 비용을 시작 시점으로 옮깁니다. 이미 완성된 캐시는 API 호출 없이 건너뛰므로 재시작 비용은 거의 없습니다. TTS 제공자 장애 등으로 준비에 실패하면 경고만 남기고 서버는 그대로 시작합니다(통화 중 합성으로 대체). 시작을 지체 없이 하려면 `PREBUILD_TTS_CACHE=0`으로 끌 수 있습니다. 호스트에서 직접 실행할 때는 `./cache-tts.sh`를 먼저 돌리세요.

문구·음성 ID·TTS 모델·속도가 바뀌면 파일명 해시가 달라지므로, 더 이상 어떤 템플릿으로도 도달할 수 없는 MP3·WAV 쌍은 기본적으로 정리합니다. 캐시 폴더에서 `<sha256>.mp3`와 `<sha256>.8k.wav` 두 형식만 대상으로 하며 다른 파일은 건드리지 않습니다. 실행 결과의 `정리` 수로 확인합니다.

수동으로 실행하려면 Docker에서는 `docker compose exec verification /app/cache-tts.sh`를 사용합니다. `--force`도 같은 방식으로 전달합니다. `agent.json`에 새 템플릿이나 변형을 추가하면 다음 실행에서 변수 없는 문구가 자동으로 포함됩니다. 변수에 따라 바뀌는 문구는 통화별로 생성합니다.

직렬 장치 접근 권한이 필요합니다. 동일 모뎀을 쓰는 기존 끝말잇기 프로그램은 종료하고 실행합니다. `EP06_RESET_BEFORE_CALL=0`이 기본값입니다. 매 통화 재부팅이 필요한 환경에서만 `1`로 바꾸세요. Docker에서는 재부팅 후 장치 번호가 바뀔 수 있으므로 호스트에서 먼저 재부팅하는 편이 적합합니다.

## HTTP API

모든 통화 API에 `Authorization: Bearer <VERIFICATION_API_KEY>`가 필요합니다. `/healthz`는 인증 없이 worker 생존 및 결과 저장 가능 여부를 반환합니다. 결과 저장이 계속 실패하면 503 `result_storage_unavailable`을 반환하고 새 통화 접수도 503으로 거부합니다.

호출 시스템의 기본 흐름은 다음과 같습니다.

1. 가게의 전화번호·상호·주소와 확인 질문을 `POST /v1/verification-call`로 제출합니다.
2. 응답의 `verificationId`를 저장하고 `GET /v1/verification-call/{verificationId}`로 상태를 조회합니다. 결과를 별도로 푸시하는 웹훅은 없습니다.
3. 상태가 `COMPLETED` 또는 `FAILED`가 되면 `answers`를 읽습니다. `FAILED`에서도 부분 답변이 있을 수 있으므로 업무 정책에 따라 검토합니다.

### POST /v1/verification-call

[`examples/request.json`](examples/request.json)에 질문 2종을 포함한 요청 예제가 있습니다. `target`에는 발신할 `phoneNumber`와 통화에서 사용할 `storeName`, `storeLocation`을 넣습니다. 각 질문에는 고유한 `id`를 지정합니다.

| 질문 유형 | 요청 필드 | 통화에서 확인하는 내용 |
| --- | --- | --- |
| `VALUE_CONFIRMATION` | `field`, `naturalField`, `currentValue`, `expectedValue` | 예상 값이 맞는지 묻고, 아니라면 현재 값을 확인합니다. |
| `CHANGE_DETECTION` | `fields` 배열의 각 `field`, `naturalField`, `currentValue` | 등록된 값이 그대로인지 각 필드를 순서대로 확인합니다. |

`field`는 호출 시스템이 결과를 연결할 때 사용할 키이고, `naturalField`는 통화에서 읽을 항목명입니다. `currentValue`는 관리 시스템에 현재 등록된 값이며 `expectedValue`는 별도로 확인하려는 예상 값입니다. 두 질문 유형 모두 결과에는 질문 `id`가 `questionId`로 들어갑니다. JSON에는 주석과 마지막 쉼표를 넣지 않습니다.

```bash
curl --fail-with-body "http://localhost:${BACKEND_PORT:-8000}/v1/verification-call" \
  -H "Authorization: Bearer $VERIFICATION_API_KEY" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/request.json
```

접수가 완료되면 **202 Accepted**와 UUID를 반환합니다. 합성·발신·통화 완료를 기다리지 않습니다.

```json
{"verificationId":"11111111-2222-4333-8444-555555555555"}
```

`phoneNumber`의 하이픈·공백·괄호를 제거하며, 숫자 7~15자리와 맨 앞 `+`만 허용합니다. 질문 `id`는 요청 안에서 유일해야 합니다. 같은 `field`가 서로 다른 질문에 반복되는 것은 허용합니다. 값은 **JSON boolean 또는 string**만 허용하고 숫자를 자동 변환하지 않습니다. `VALUE_CONFIRMATION`의 현재 값과 예상 값은 같은 타입이어야 합니다.

### GET /v1/verification-call/{verificationId}

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $VERIFICATION_API_KEY" \
  "http://localhost:${BACKEND_PORT:-8000}/v1/verification-call/11111111-2222-4333-8444-555555555555"
```

```json
{
  "verificationId":"11111111-2222-4333-8444-555555555555",
  "status":"COMPLETED",
  "answers":[
    {"questionId":"q1","field":"storeName","value":"새봄식당"},
    {"questionId":"q2","field":"storeName","value":"새봄식당"},
    {"questionId":"q2","field":"storeLocation","value":"서울특별시 강남구 테헤란로 123, 4층 401호"},
    {"questionId":"q2","field":"businessHours","value":"09:00~18:00"}
  ],
  "createdAt":"2026-11-01T00:00:00+00:00",
  "startedAt":"2026-11-01T00:00:04+00:00",
  "connectedAt":"2026-11-01T00:00:10+00:00",
  "endedAt":"2026-11-01T00:01:10+00:00",
  "failureReason":null
}
```

위는 응답 형식 예시입니다. `questionId`는 동일 필드를 여러 질문에서 물었을 때 결과를 구분합니다. 질문 순서와 `fields` 순서대로 답변을 보존합니다. 이전 질문 결과를 다른 질문에 자동 복사하지 않습니다. 모르는 값은 `null`이나 추정값으로 채우지 않고 `answers`에서 제외합니다. 진행 중에도 확인된 답변을 조회할 수 있습니다. 시각은 UTC ISO 8601입니다.

| 상태 | 의미 |
| --- | --- |
| PENDING | 큐 대기 또는 발신 전 TTS 준비·모뎀 초기화 |
| IN_PROGRESS | 발신 명령 전송 직전부터 연결 대기·상담·종료 인사·장치 정리까지 |
| COMPLETED | 모든 필드를 확인하고 통화를 마침 |
| FAILED | 연결 실패, 확인 미완료, 응답 거부, 기술적 실패 등 |

주요 `failureReason`: `CONNECTION_FAILED`, `NO_ANSWER_OR_REJECTED`, `WRONG_TARGET`, `IDENTITY_UNCONFIRMED`, `USER_REQUESTED_STOP`, `REMOTE_HANGUP`, `INPUT_TIMEOUT`, `WAIT_TIMEOUT`, `QUEUE_EXPIRED`, `CALL_TIME_LIMIT`, `TURN_LIMIT`, `PROCESSING_ERROR`, `SERVER_RESTARTED`, `SERVER_SHUTDOWN`.

통화 도중 종료를 요청하면 인사 후 종료하며 미수집 항목이 있으면 FAILED입니다. 그 전에 확인한 답변은 보존합니다. 모든 답변을 수집한 뒤 마무리 중 종료를 요청하거나 상대방이 먼저 끊으면 COMPLETED입니다. STT/TTS/Jev 등의 기술적 실패는 부분 답변과 함께 FAILED로 남깁니다. 기술적 실패 후 자동 재발신은 하지 않습니다.

잘못된 요청은 422, 인증 오류는 401, 없는 UUID는 404, 큐 포화는 429, worker 중단은 503입니다. POST 재전송은 별도 통화 요청이므로 클라이언트에서 응답을 확인한 요청을 중복 제출하지 마세요. 요청당 최대 질문 30개, 전체 필드 60개입니다.

## 전화와 AI 구성

| 역할 | 구현 |
| --- | --- |
| 모뎀 제어 | EP06-E, AT 포트 115200 baud, `ATD<number>;` → `AT+CLCC` → `AT+CHUP` |
| 연결 판정 | `+CLCC`에서 발신(`dir=0`)·연결(`stat=0`)·음성(`mode=0`) 확인 |
| 통화 음성 | `AT+QPCMV=1,0`, USB NMEA 직렬 포트, 8kHz / mono / PCM16 |
| STT | 기존 `mlapi.run` Whisper 호환 `/v1/audio/transcriptions`, 기본 `whisper-large-v3` |
| 의미 분석 | OpenAI 호환 게이트웨이의 `/chat/completions`, 기본 `gpt-6.1-terra` (`temperature` 미지정) |
| TTS | ElevenLabs `/v1/text-to-speech/{voice_id}` (`ELEVENLABS_VOICE_ID`), 기본 모델 `eleven_multilingual_v2` |
| 발화 제어·답변 분류·끼어들기 판정 | 요청에 지정된 Jev `/v1/decisions`, `typesafe/jev-latest` |
| HTTP/저장 | FastAPI, SQLite WAL |

모뎀 오디오 기본값 `QAUDMOD=3`, `QRXGAIN=8192`, `QMIC=16384,1024`, `QSIDET=0`을 기존 구성에서 이어받았습니다. 통화 전 값을 읽고 설정을 검증한 뒤 종료 시 복원합니다. GPS NMEA 출력 충돌을 방지하고, 종료 시 `QPCMV=0`을 보냅니다. 실제 Voice over USB 지원 여부는 모뎀 펌웨어와 통신망에 달려 있습니다.

STT 입력은 **항상 FFmpeg로 16kHz / mono / PCM16 WAV로 변환**합니다. 기존 highpass, lowpass, 잡음 감소 필터를 유지합니다. STT 클라이언트도 WAV 형식을 재검사하여 8kHz 파일이 직접 전송되는 것을 막습니다. TTS는 MP3를 8kHz WAV로 변환해 NMEA 포트에 100ms 단위로 전송합니다.

## 대화 규칙

상태 머신이 질문 진행과 완료 여부를 관리합니다. Jev는 각 발화를 `YES/NO/VALUE/CUSTOM/UNCLEAR`와 `STOP/WAIT/CONTINUE/REPEAT`로 분류합니다. 확신도 기준 이상인 `YES/NO/UNCLEAR`는 상태 머신에 바로 전달합니다. `VALUE`이면 LLM은 발화에 명시된 값과 원문 근거만 추출하고, 상태 머신이 기존 값·예상 값과 비교해 저장 또는 복창 여부를 정합니다. 값이 불명확하거나 Jev의 답변 판정 신뢰도가 낮으면 LLM을 호출하지 않고 `retry` 또는 관계자 확인 단계의 `identityRetry`로 재질문합니다. `REPEAT`는 상대방이 명시적으로 다시 말해 달라고 요청했다고 Jev가 기준 신뢰도 이상으로 판정했을 때만 사용하며, `repeat` 문구로 방금 말한 질문을 다시 읽습니다. `CUSTOM`은 확인 통화와 관련된 별도 질문을 위한 경로입니다. LLM이 전체 대화 이력, 대상 매장 정보, `agent.json`의 지식을 받아 `reply` 문장만 생성하고 상태 머신이 현재 질문을 다시 붙여 발화합니다. 근거가 부족하면 확인할 수 없다고 답합니다. 이 과정에서 질문 단계·후보값·저장된 답변은 바뀌지 않습니다. Jev가 프롬프트 인젝션이나 확인 업무와 무관한 요청으로 판정하면 LLM 호출 없이 `retry` 문구로 재질문합니다. LLM 결과는 Pydantic으로 검증합니다.

- 먼저 대상 매장 관계자인지 확인합니다. 이때의 “네”를 첫 질문의 답으로 저장하지 않습니다. 부정하면 종료하고, 확인 불가가 반복되면 FAILED로 마칩니다.
- 새 문자열 값을 복창하는 READBACK 단계에서는 단순 “네”만으로 저장하지 않고, 상대방이 값을 다시 말해야 확정합니다. 이때 Jev의 YES 신뢰도 기준은 기본 0.9입니다.
- VALUE_CONFIRMATION은 예상 값이 현재 사실인지 묻습니다. 예상 값에 “아니요”라고 하면 현재 등록 값을 다시 묻습니다. 문자열에서 예상 값 부정만으로 기존 값이 맞다고 단정하지 않습니다.
- CHANGE_DETECTION은 각 필드를 순서대로 확인합니다. 변경됐다고 하면 현재 값을 묻습니다.
- 새 문자열·boolean 값을 들으면 복창하고 확인을 받아 저장합니다. 명시적 CURRENT/EXPECTED 답변은 발화 속 근거 문자열이 있는지 검증합니다. Boolean `false`를 누락이나 문자열로 바꾸지 않습니다.
- 늦게 처리된 이전 질문의 “네”가 다음 필드를 확정하지 않도록 발화 시작 시점과 질문 재생 시점을 비교합니다. 이런 발화는 기록하고 현재 질문을 다시 확인합니다.
- 마지막 확인 안내 도중 정정하면 마지막 필드를 다시 열어 확인합니다. 마지막 필드가 아닌 이전 정보의 복잡한 정정은 별도 재확인 통화로 처리하는 것이 좋습니다.

페르소나, 상담 지식, 안내 문구, boolean 읽기 표현은 **`agent.json` 한 파일**에 있습니다. 각 통화 시작 시 읽으므로 다음 통화부터 수정이 적용됩니다. `templates`의 각 항목은 문자열 하나 또는 문자열 배열로 적을 수 있습니다. 배열이면 사용할 때마다 문구 하나를 무작위로 선택합니다. `booleanLabels`에 필드별 true/false 표현을 추가할 수 있습니다. 일반 지식 질문은 해당 파일의 지식만 근거로 간단히 답하도록 프롬프트에 제한했습니다.

## 발화 중단과 추임새

녹음 스레드는 TTS 재생과 STT/Jev/LLM/TTS 요청 중에도 계속 동작합니다. 기본 600ms 무음으로 발화 종료를 판정합니다.

1. 녹음을 16kHz로 변환해 STT를 수행한 뒤, 모든 비어 있지 않은 인식 텍스트를 Jev로 검사합니다.
2. Jev는 음성 인식 오류처럼 무의미한 입력인지 `irrelevant`로 판정하고, 공격·업무 외 요청인지 `unsafe_custom`으로 검사하며, `STOP/WAIT/CONTINUE/REPEAT`와 `YES/NO/VALUE/CUSTOM/UNCLEAR`를 함께 고릅니다. 발화 시작 시각이 TTS 재생 구간과 겹쳤으면 `interrupt`, `backchannel`도 같은 요청에서 판정합니다.
3. 명백히 무관한 입력은 `turn.ignored`로 기록하고 대화에 반영하지 않습니다. 재생 중인 TTS는 계속 출력합니다. 모호한 판정은 무시하지 않습니다.
4. 중단 의도가 기준값 이상이면 송신 재생을 중지합니다. **동일 STT 텍스트**를 재사용해 대화를 진행합니다. 단순 추임새면 재생을 유지하고 텍스트를 버퍼에 보관합니다. 예를 들어 “네네” 다음에 “아직 땡땡식당입니다”가 들어오면 다음 의미 분석 입력은 “네네 아직 땡땡식당입니다”입니다. 추임새만으로 값을 확정하지 않습니다.
5. `STOP`이면 종료 인사 후 통화를 끊습니다. `WAIT`이면 재생을 멈추고 `agent.json`의 `waitAcknowledgement` 안내를 말한 뒤 같은 질문을 유지한 채 상대방의 다음 응답을 기다립니다. 다음 발화가 답변이면 대화를 재개하고, “이제 말씀하세요”처럼 답변이 없는 재개 신호이면 질문을 다시 읽습니다. 최초 `WAIT`부터 최대 60초(`WAIT_TIMEOUT_SECONDS`, 기본 60, 최대 60)가 지나면 종료 인사 후 끊습니다. 반복된 `WAIT`도 기한을 연장하지 않습니다. `REPEAT`이면 명시적인 재청취 요청에 한해 `agent.json`의 `repeat` 문구로 방금 말한 질문을 다시 읽고 질문 상태를 유지합니다. 인식이 불분명하면 `REPEAT`가 아니라 재질문 경로를 사용합니다. `CONTINUE`이면 일반 답변으로 처리합니다. 모호한 끼어들기 판정은 상대에게 발언권을 줍니다. Jev 요청이나 응답 형식이 실패하면 같은 질문을 다시 묻습니다. 재질문 자체가 다음 응답까지의 간격을 주므로 추가 대기는 넣지 않습니다. 연속 실패가 `JEV_MAX_FAILURES`(기본 3, 최대 5)에 도달하면 준비된 오류 안내 후 FAILED로 종료합니다.

STT가 끝났을 때 TTS가 이미 끝났더라도 실제 녹음 시작 시각을 사용하므로 중간 발화가 일반 응답으로 잘못 분류되는 것을 줄입니다. RMS VAD이며 별도 AEC는 없습니다. 실제 통화 환경의 에코·배경음에 따라 `SPEECH_RMS_THRESHOLD`, `SILENCE_SECONDS`, `JEV_THRESHOLD`를 조정해야 합니다.

## 큐, 재시작, 속도

SQLite 자체가 FIFO 큐입니다. 접수는 트랜잭션으로 저장되고 worker 하나가 원자적으로 요청을 가져옵니다. 모뎀 잠금 파일과 DB 잠금 파일로 중복 worker 실행도 차단합니다. 서버는 반드시 `--workers 1`로 실행하고, 같은 모뎀에 컨테이너를 여러 개 띄우지 않습니다. `MODEM_LOCK_FILE`은 같은 모뎀을 사용하는 프로세스 사이에 공통 경로여야 합니다.

발신 전 첫 인사와 첫 질문의 TTS·8kHz 변환을 완료합니다. 인사말 변형도 통화마다 하나만 선택합니다. `agent.json`의 템플릿을 확인해 변수가 없는 문구와 매장 정보만으로 완성되는 문구를 미리 준비하되, 관계자 재확인(`identityRetry`), 인사말의 다른 변형, 첫 질문에 쓰이는 `expected`·`current` 템플릿은 이 일괄 준비에서 제외합니다. 관계자 재확인과 첫 질문은 각각 변형 중 하나를 무작위로 미리 골라 그 문구만 준비하고, 통화 중에도 같은 문구를 사용합니다. 관계자 재확인은 자주 쓰는 경로라 통화 중 합성 지연을 없애려고 발신 전에 준비합니다. 각 질문 단계에서는 한 변형을 고정해 되물을 때 재사용합니다. `llmWait`의 대기 안내도 모두 준비하고, Jev 판정 후 LLM 값 추출이나 `CUSTOM` 답변 생성이 필요할 때 무작위 문구를 LLM 요청과 동시에 재생합니다. 이 안내는 질문 상태를 바꾸지 않습니다. 준비 실패 시 발신하지 않습니다. 연결 후에는 녹음 스트림이 준비되는 대로 캐시된 첫 인사를 재생합니다.

빠른 응답을 위해 HTTP 연결 재사용, 600ms VAD, 템플릿 기반 질문과 TTS/변환 결과 캐시를 사용합니다. 변수가 없는 템플릿은 `data/tts-static/`에 MP3와 8kHz WAV를 저장해 다음 통화에서도 재사용합니다. 변수 문구는 통화별 `data/calls/<verificationId>/tts/`에 저장합니다. 공용 캐시의 파일명은 문구·음성 ID·TTS 모델·속도를 반영하므로 이 값이 바뀌면 다시 합성합니다. 쓰이지 않게 된 파일은 캐시를 다시 만들 때 정리합니다. STT는 발화 단위, TTS는 완성된 음성 단위이며 스트리밍 추론은 아닙니다. 실제 응답 지연은 STT/Jev/LLM/TTS 서비스에 따라 달라지며 실측 성능을 보장하지 않습니다. `turn.response_ready.since_utterance_end_ms` 로그로 측정할 수 있습니다.

프로세스 종료 시 진행 중 통화의 정리와 장치 해제를 기다립니다. 통화 종료 결과는 최종 상태 기록 전에 별도 트랜잭션으로 남겨 재시작 후에도 완료 여부를 복구합니다. 종료 결과 자체를 기록하지 못한 통화는 재시작 시 `SERVER_RESTARTED`로 실패 처리하여 중복 발신을 피합니다. 저장 장애가 계속되면 추가 발신을 멈추고 `/healthz`와 새 통화 접수를 503으로 응답합니다. 아직 가져가지 않은 요청은 `PENDING_MAX_AGE_SECONDS`(기본 900초) 이내일 때만 발신하고, 지난 요청은 `QUEUE_EXPIRED`로 종료합니다. `QUEUE_CAPACITY`는 진행 중 요청을 포함한 상한입니다.

## 파일 구조와 테스트

| 경로 | 내용 |
| --- | --- |
| `verification/api.py` | HTTP 계약, 인증, lifespan |
| `verification/store.py`, `worker.py` | SQLite 영속 큐, 결과, 단일 worker |
| `verification/dialogue.py` | 관계자 확인, 질문 상태, 답변 저장 규칙 |
| `verification/providers.py` | STT·LLM·TTS·Jev HTTP 클라이언트 |
| `verification/runtime.py` | 발신 전 준비, 녹음·재생·대화 진행, 종료 |
| `verification/hardware/` | 기존 프로젝트에서 가져와 조정한 모뎀·오디오·VAD·로그 |
| `agent.json` | 페르소나·지식·문구 |
| `examples/` | 요청 및 OpenAPI 스키마 |
| `tests/` | 계약, 큐 동시성·복구, 대화, 모의 통화, 오디오/모뎀 회귀 검사 |

```bash
python -m unittest discover -s tests -v
```

테스트는 외부 API와 실제 모뎀 없이 동작합니다. `ffmpeg`가 PATH에 있으면 실제 PCM 변환 검사도 실행합니다. 실제 프로세스·파이프·스레드로 동시 녹음/재생을 검사하고, 외부 API는 `httpx.MockTransport`로 검사합니다. 실행 결과와 검증 범위는 `VALIDATION.md`에 기록했습니다.

`data/calls.sqlite3`에는 요청·결과가, `data/calls/<verificationId>/`에는 발화 WAV, TTS, JSONL 로그가 저장됩니다. 통화 음성과 매장 정보가 포함되므로 배포 환경에서 디렉터리 접근·보관 기간을 설정하세요. 녹음은 STT 서버, 음성 합성 텍스트는 ElevenLabs, 대화 텍스트는 LLM 및 Jev 서버로 전송됩니다.

`docker compose logs -f verification`에서는 재생을 시작한 상담사 답변(`assistant.response`)과 STT 전사(`user.transcript`)가 통화 ID·턴과 함께 한 줄 JSON으로 출력됩니다. 모뎀·오디오 상세 이벤트는 통화별 JSONL 파일에서 확인합니다. 서버 시작과 오류 등 기본 운영 로그는 별도로 출력될 수 있습니다.

Jev는 [공식 System One의 noul 요청·응답 계약](https://docs.typesafe.ai/api/system-one)을 참고하되, URL과 모델은 요청에서 지정한 게이트웨이 값을 사용합니다. 해당 게이트웨이의 실제 인증과 호환 응답은 운영 키로 확인해야 합니다. TTS 계약은 [ElevenLabs 공식 문서](https://elevenlabs.io/docs/api-reference/text-to-speech/convert)를 참고했습니다.
TTS는 발화 속도 `0.98`로 요청하며 문장 사이의 마침표·물음표·느낌표 뒤에 `<break time="0.2s" />`를 넣습니다. 마지막 문장 뒤에는 붙이지 않으며 소수점과 이미 들어 있는 쉼 태그는 중복 처리하지 않습니다.
