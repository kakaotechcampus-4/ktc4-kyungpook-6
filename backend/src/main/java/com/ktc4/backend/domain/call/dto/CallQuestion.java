package com.ktc4.backend.domain.call.dto;

import java.util.List;

/**
 * 통화에서 확인할 질문 하나. 전화 API 의 {@code questions[]} 항목 하나가 된다.
 *
 * <p>두 묻는 방식을 한 타입으로 들고 쓰는 칸만 다르게 둔다 — 전화 API 도 한 배열에 섞어 받고,
 * 타입을 나누면 질문 목록이 두 종류의 목록으로 갈라져 순서를 유지하기 어려워진다.
 *
 * @param id            질문을 구분하는 id. 한 요청 안에서 겹치면 안 되고, 답이 이 값으로 돌아온다
 * @param type          묻는 방식
 * @param fields        물을 항목들. {@link CallQuestionType#VALUE_CONFIRMATION} 은 반드시 하나다
 * @param expectedValue 맞는지 확인하려는 값. {@link CallQuestionType#VALUE_CONFIRMATION} 에만 있다
 */
public record CallQuestion(String id, CallQuestionType type, List<CallQuestionField> fields, String expectedValue) {

    public CallQuestion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("질문 id 가 없습니다");
        }
        if (type == null) {
            throw new IllegalArgumentException("질문 방식이 없습니다");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("물을 항목이 없는 질문은 만들 수 없습니다 - id=" + id);
        }
        fields = List.copyOf(fields);
        // 전화 API 는 VALUE_CONFIRMATION 을 항목 하나(field/currentValue/expectedValue)로 받는다.
        // 두 항목을 한 질문에 담으면 어느 항목의 expectedValue 인지가 사라진다.
        if (type == CallQuestionType.VALUE_CONFIRMATION) {
            if (fields.size() != 1) {
                throw new IllegalArgumentException("확인 질문은 항목이 하나여야 합니다 - id=" + id + ", 항목 " + fields.size() + "개");
            }
            if (expectedValue == null || expectedValue.isBlank()) {
                throw new IllegalArgumentException("확인 질문에 확인할 값이 없습니다 - id=" + id);
            }
        } else if (expectedValue != null) {
            throw new IllegalArgumentException("변화 확인 질문에는 확인할 값을 담지 않습니다 - id=" + id);
        }
    }

    /** 등록된 값들이 바뀌었는지 묻는 질문. */
    public static CallQuestion changeDetection(String id, List<CallQuestionField> fields) {
        return new CallQuestion(id, CallQuestionType.CHANGE_DETECTION, fields, null);
    }

    /** 조사가 찾아낸 값이 맞는지 묻는 질문. */
    public static CallQuestion valueConfirmation(String id, CallQuestionField field, String expectedValue) {
        return new CallQuestion(id, CallQuestionType.VALUE_CONFIRMATION, List.of(field), expectedValue);
    }
}
