package com.ktc4.backend.domain.file.client;

import java.time.LocalDate;

/**
 * AI 서버가 사업자등록증에서 읽은 값 — {@code ai/src/biz_cert/models.py} 의 {@code BizCertFields} 와 같은 칸이다.
 * 못 읽은 칸은 null 이다. AI 가 이미 다듬어 보낸다(사업자번호는 숫자 10자리, 개업일은 날짜).
 */
public record AiBizCertReading(
        String bizNo,
        String storeName,
        String representativeName,
        String storeAddress,
        LocalDate openDate,
        String corpName,
        String businessType,
        String businessItem
) {
}
