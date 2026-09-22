package com.ktc4.backend.domain.store.enums;

// AI 조사 자료 목록을 거르는 기준. 대응이 다른 두 갈래라 목록도 나눠서 볼 수 있게 한다.
public enum NtsCheckFilter {
    STATUS_MISMATCH, // 우리 상태와 국세청 상태가 다름 — AI 조사 대상
    DATA_PROBLEM     // 사업자번호가 없거나 국세청에 없는 번호 — 사람이 데이터를 고칠 대상
}
