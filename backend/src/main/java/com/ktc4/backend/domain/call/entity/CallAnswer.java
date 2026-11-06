package com.ktc4.backend.domain.call.entity;

/**
 * 통화에서 확인된 값 한 건. 전화 API 응답의 {@code answers} 항목 하나를 그대로 담는다.
 *
 * <p>{@link VerificationCall#getAnswers()} 안에 JSON 으로 저장된다 — 답은 가게 정보가 아니라
 * "그때 점주가 뭐라고 답했는지"의 기록이라 항목별 컬럼으로 펼치지 않는다.
 *
 * <p>{@code value} 는 전화 API 에서 문자열이나 JSON 불리언으로 오는데 여기서는 문자열 하나로 받는다
 * (불리언은 {@code "true"}·{@code "false"}). 참·거짓으로 묻는 항목은 영업 여부 하나뿐이고
 * ({@link com.ktc4.backend.domain.call.enums.CallField#isBoolean()}), 어느 항목이 그런지는 우리가
 * 질문을 만들 때 이미 정하므로 값에 타입을 남겨 둘 이유가 없다.
 *
 * @param questionId 이 답이 속한 질문의 id
 * @param field      전화 API 의 항목 키 ({@code CallField.apiField()})
 * @param value      통화에서 확인된 값
 */
public record CallAnswer(String questionId, String field, String value) {
}
