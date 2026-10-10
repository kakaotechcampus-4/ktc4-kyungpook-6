package com.ktc4.backend.domain.investigation.client;

/** AI 호출 실패: 읽기 타임아웃 — AI 가 아예 답하지 않았다. 다시 기다리면 그 가게에 시간을 두 배로 쓰므로 재시도하지 않는다. */
public class AiReadTimeout extends AiException {

    public AiReadTimeout(String message) {
        super(message);
    }

    public AiReadTimeout(String message, Throwable cause) {
        super(message, cause);
    }
}
