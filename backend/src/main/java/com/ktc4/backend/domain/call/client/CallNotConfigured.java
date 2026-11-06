package com.ktc4.backend.domain.call.client;

/**
 * 전화 API 주소나 키가 없어 부를 수 없다.
 *
 * <p>키를 못 받은 환경(로컬·아직 값을 안 넣은 서버)에서도 앱은 떠야 한다. 그래서 기동을 막지 않고
 * 전화를 걸려고 할 때만 이것으로 거절한다 — AI 조사가 키 없이 503 으로 답하는 것과 같은 방식이다.
 */
public class CallNotConfigured extends CallException {

    public CallNotConfigured(String message) {
        super(message);
    }
}
