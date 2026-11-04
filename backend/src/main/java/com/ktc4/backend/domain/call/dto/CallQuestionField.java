package com.ktc4.backend.domain.call.dto;

import com.ktc4.backend.domain.call.enums.CallField;

/**
 * 질문 하나가 다루는 항목과 그 항목의 현재 등록 값.
 *
 * @param field        물을 항목
 * @param currentValue 우리 DB 에 지금 들어 있는 값. 영업 여부면 {@code "true"}·{@code "false"}
 */
public record CallQuestionField(CallField field, String currentValue) {

    public CallQuestionField {
        if (field == null) {
            throw new IllegalArgumentException("물을 항목이 없습니다");
        }
        if (currentValue == null || currentValue.isBlank()) {
            // 현재 값이 없으면 "바뀌었나" 도 "이 값이 맞나" 도 물을 수 없다 — 질문을 만드는 쪽이 걸러야 한다.
            throw new IllegalArgumentException("현재 값이 없는 항목은 전화로 물을 수 없습니다 - field=" + field);
        }
    }
}
