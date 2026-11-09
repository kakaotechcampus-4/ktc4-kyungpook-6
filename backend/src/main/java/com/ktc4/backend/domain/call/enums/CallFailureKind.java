package com.ktc4.backend.domain.call.enums;

import java.util.Map;

/**
 * 전화가 실패한 이유를 <b>담당자가 할 일</b>로 묶은 값.
 *
 * <p>전화 API 가 주는 {@code failureReason} 코드는 "무슨 일이 있었나" 를 말하지만, 담당자에게 필요한
 * 것은 "그래서 내가 뭘 해야 하나" 다. 통화 중이라 못 들은 것과 없는 번호라 영영 못 거는 것은 둘 다
 * "실패" 지만 할 일이 정반대다 — 앞은 이따 다시 걸면 되고, 뒤는 번호를 고쳐야 한다.
 *
 * <p>특히 {@link #OUR_SIDE} 를 가르는 것이 중요하다. 우리 쪽이나 전화 API 쪽 문제를 다른 실패와 같은
 * 모양으로 보여 주면 담당자가 <b>가게 탓으로 읽는다</b> — 멀쩡한 가게를 "연락이 안 되는 가게" 로
 * 넘기게 된다. 2026-11-04 에 실제로 그렇게 읽었다(전화 API 가 {@code PROCESSING_ERROR} 로 즉시 실패했고,
 * 벨도 울리지 않았는데 화면은 안 받은 전화와 똑같이 보였다).
 *
 * <p>코드 목록은 2026-11-04 에 전화 API 쪽에서 받았다(PR #153 코멘트). 그래도 모르는 코드는
 * {@link #UNKNOWN} 으로 두고 받은 코드를 화면에 그대로 보여 준다 — 목록이 늘어날 수 있고,
 * 삼키면 원인이 사라진다. 코드가 추가되면 {@link #BY_REASON} 만 채우면 된다.
 */
public enum CallFailureKind {

    /** 이따 다시 걸면 된다 — 통화 중, 안 받음, 전원 꺼짐, 통화가 중간에 끊김. */
    RETRY_LATER,

    /** 번호가 잘못됐다 — 없는 번호, 다른 사람이 쓰는 번호. 다시 걸어도 같다. 번호를 고쳐야 한다. */
    FIX_NUMBER,

    /** 전화로는 확인할 수 없다 — 수신 거부, 자동응답만 받음. 다른 방법으로 확인해야 한다. */
    NOT_BY_PHONE,

    /**
     * 연결은 됐는데 <b>상대가 맞는지 확인하지 못했다</b>. 한 번은 다시 걸어 보고, 또 그러면 번호를 본다.
     *
     * <p>{@link #RETRY_LATER} 와 {@link #FIX_NUMBER} 를 가를 수 없는 자리라 따로 둔다. 전화가 꺼져 있어
     * 통신사 안내음에 연결된 것일 수도 있고(다시 걸면 된다), 번호가 낡아 가게와 무관한 곳에 닿은
     * 것일 수도 있다(번호를 고쳐야 한다). 사유 코드 하나로는 둘을 가릴 수 없고, 담당자가 한 번 더
     * 걸어 보면 갈린다.
     */
    PARTY_UNCONFIRMED,

    /** 가게와 무관하다 — 전화 API 나 우리 쪽 문제. 가게 탓으로 읽으면 안 된다. */
    OUR_SIDE,

    /** 아직 분류하지 못한 코드. 화면에는 받은 코드를 그대로 보여 준다. */
    UNKNOWN;

    /**
     * 전화 API 가 상태를 끝으로 옮겨 주지 않아 우리가 닫았을 때 적는 코드.
     * 전화 API 의 코드와 겹치지 않도록 접두어를 붙인다.
     */
    public static final String GAVE_UP_WAITING = "KTC4_GAVE_UP_WAITING";

    /**
     * 전화 API 가 주는 사유 코드를 담당자가 할 일로 묶는다.
     *
     * <p>목록은 2026-11-04 에 전화 API 쪽에서 받았다(PR #153 코멘트). 그전에는 상황을 직접 만들어
     * 받아 본 셋뿐이었고 나머지는 {@link #UNKNOWN} 이었다.
     *
     * <p>가르는 기준이 "무슨 일이 있었나" 가 아니라 <b>"담당자가 뭘 해야 하나"</b> 라, 전화 API 의
     * 코드와 일대일이 아니다. 예를 들어 {@code NO_ANSWER_OR_REJECTED} 는 안 받은 것과 거절한 것이
     * 한 코드에 묶여 있는데 둘을 가를 수 없으니 <b>덜 단정적인 쪽</b>(다시 걸어 보기)으로 둔다.
     */
    private static final Map<String, CallFailureKind> BY_REASON = Map.ofEntries(
            // 우리가 적는 코드
            Map.entry(GAVE_UP_WAITING, OUR_SIDE),

            // 가게와 무관 — 전화 API 쪽 장치·서버
            Map.entry("CONNECTION_FAILED", OUR_SIDE),   // 모뎀에서 발신에 실패
            Map.entry("PROCESSING_ERROR", OUR_SIDE),    // STT·LLM·TTS·장치 오류
            Map.entry("SERVER_RESTARTED", OUR_SIDE),
            Map.entry("SERVER_SHUTDOWN", OUR_SIDE),

            // 이따 다시 걸면 된다
            Map.entry("NO_ANSWER_OR_REJECTED", RETRY_LATER),  // 안 받음과 거절이 한 코드다 — 덜 단정적인 쪽으로
            Map.entry("REMOTE_HANGUP", RETRY_LATER),          // 마치기 전에 끊음. 들은 답은 함께 온다
            Map.entry("INPUT_TIMEOUT", RETRY_LATER),          // 정해진 시간 동안 응답 없음
            Map.entry("WAIT_TIMEOUT", RETRY_LATER),           // "잠깐만요" 하고 정해진 시간 안에 돌아오지 않음
            Map.entry("CALL_TIME_LIMIT", RETRY_LATER),        // 최대 통화 시간 초과
            Map.entry("TURN_LIMIT", RETRY_LATER),             // 최대 대화 턴 초과

            /*
              번호가 잘못됐다 — 전화를 받은 사람이 "확인 대상이 아니다" 라고 직접 답한 경우다.
              다시 걸어도 같은 사람이 받는다. 우리가 만들어 볼 수 없던 "없는 번호" 자리를 이것이 메운다.
            */
            Map.entry("WRONG_TARGET", FIX_NUMBER),

            // 상대를 확정하지 못했다 — 다시 걸어 보고, 또 그러면 번호를 본다
            Map.entry("IDENTITY_UNCONFIRMED", PARTY_UNCONFIRMED),

            /*
              상대가 "그만하자" 고 말한 경우다. 바로 다시 거는 것은 민폐라 전화로는 더 묻지 않는다 —
              버튼은 열어 두되(담당자가 판단할 수 있게) 안내는 다른 방법을 가리킨다.
            */
            Map.entry("USER_REQUESTED_STOP", NOT_BY_PHONE));

    /**
     * 실패 사유 코드를 담당자가 할 일로 바꾼다.
     *
     * @param failureReason 전화 API 가 준 코드. 없으면 {@code null}
     * @return 해당 분류. 모르는 코드거나 사유가 없으면 {@link #UNKNOWN}
     */
    public static CallFailureKind of(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return UNKNOWN;
        }
        return BY_REASON.getOrDefault(failureReason.strip(), UNKNOWN);
    }
}
