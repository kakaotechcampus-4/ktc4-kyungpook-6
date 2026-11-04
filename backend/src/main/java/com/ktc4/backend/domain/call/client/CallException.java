package com.ktc4.backend.domain.call.client;

/**
 * 전화 API 호출 실패. 부르는 쪽이 하위 타입으로 처리를 나눈다 — 다시 시도할 것과, 다시 보내도 같을 것.
 *
 * <p>HTTP 결과를 이 타입으로 번역하는 일은 {@link CallApiClient} 한 곳에서만 한다
 * ({@code AiApiClient} 와 같은 나눔).
 */
public abstract class CallException extends RuntimeException {

    protected CallException(String message) {
        super(message);
    }

    protected CallException(String message, Throwable cause) {
        super(message, cause);
    }
}
