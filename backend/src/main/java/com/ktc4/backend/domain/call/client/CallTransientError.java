package com.ktc4.backend.domain.call.client;

/** 전화 API 호출 실패: 5xx·연결 실패·타임아웃 — 잠깐의 장애일 수 있다. 폴러는 다음 차례에 다시 읽는다. */
public class CallTransientError extends CallException {

    public CallTransientError(String message) {
        super(message);
    }

    public CallTransientError(String message, Throwable cause) {
        super(message, cause);
    }
}
