package com.ktc4.backend.domain.investigation.client;

/**
 * AI 서버 호출 실패. 실행기가 하위 타입으로 처리를 나눈다 — 재시도할 것, 그 가게만 실패로 남길 것, Job 을 멈출 것.
 *
 * <p>나누는 기준이 곧 재시도 설계라 HTTP 결과를 이 타입으로 번역하는 일은 {@link AiApiClient} 한 곳에서만 한다.
 */
public abstract class AiException extends RuntimeException {

    protected AiException(String message) {
        super(message);
    }

    protected AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
