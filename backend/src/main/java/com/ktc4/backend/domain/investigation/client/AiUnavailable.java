package com.ktc4.backend.domain.investigation.client;

/** AI 호출 실패: 503 조사 구현 없음 — 남은 가게도 다 같은 실패라 Job 을 멈춘다. */
public class AiUnavailable extends AiException {

    public AiUnavailable(String message) {
        super(message);
    }

    public AiUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
