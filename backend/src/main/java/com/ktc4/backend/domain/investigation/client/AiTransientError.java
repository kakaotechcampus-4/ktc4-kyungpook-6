package com.ktc4.backend.domain.investigation.client;

/** AI 호출 실패: 5xx·연결 실패 — 잠깐의 장애일 수 있어 실행기가 재시도한다. */
public class AiTransientError extends AiException {

    public AiTransientError(String message) {
        super(message);
    }

    public AiTransientError(String message, Throwable cause) {
        super(message, cause);
    }
}
