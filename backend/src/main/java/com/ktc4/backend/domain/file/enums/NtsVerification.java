package com.ktc4.backend.domain.file.enums;

// 사업자등록증에서 읽은 값을 국세청 진위확인에 넣어 본 결과. 진위확인은 사업자번호·개업일·대표자 세 값이 다 있어야 부를 수 있다.
public enum NtsVerification {
    VERIFIED,   // 국세청 기록과 맞음
    MISMATCH,   // 국세청이 맞지 않다고 답함 — 잘못 읽었거나 다른 등록증
    UNVERIFIED  // 확인 못 함 — 세 값 중 못 읽은 것이 있거나 국세청을 부르지 못함
}
