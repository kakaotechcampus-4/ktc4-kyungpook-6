package com.ktc4.backend.domain.call.client;

/** 전화 API 호출 실패: 4xx 이거나 응답이 약속과 다르다. 같은 요청을 다시 보내도 같아서 재시도하지 않는다. */
public class CallContractError extends CallException {

    public CallContractError(String message) {
        super(message);
    }

    public CallContractError(String message, Throwable cause) {
        super(message, cause);
    }
}
