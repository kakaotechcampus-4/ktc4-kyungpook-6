package com.ktc4.backend.domain.investigation.client;

/** AI 호출 실패: 429 사용량 한도 — 남은 가게도 다 같은 실패라 Job 을 멈춘다. */
public class AiQuotaExceeded extends AiException {

    public AiQuotaExceeded(String message) {
        super(message);
    }

    public AiQuotaExceeded(String message, Throwable cause) {
        super(message, cause);
    }
}
