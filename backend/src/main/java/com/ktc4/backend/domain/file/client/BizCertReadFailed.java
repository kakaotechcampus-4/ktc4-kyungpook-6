package com.ktc4.backend.domain.file.client;

/** AI 서버로 사업자등록증을 읽지 못했다 — 시간 초과·연결 실패·AI 의 오류 응답. 원인 문장에 읽은 값을 싣지 않는다. */
public class BizCertReadFailed extends RuntimeException {

    public BizCertReadFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
