package com.ktc4.backend.domain.call.dto;

import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.call.enums.CallField;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** 통화에서 들은 답 한 건. */
public record CallAnswerResponse(
        @Schema(description = "우리 쪽 항목 키 — 수정안(`proposedChanges`)의 field 와 같은 값입니다. "
                + "우리가 묻지 않은 항목을 전화 API 가 답했다면 전화 API 의 키가 그대로 들어옵니다",
                example = "status")
        String field,

        @Schema(description = "통화에서 그 항목을 부른 이름. 화면 라벨로 그대로 쓸 수 있습니다", example = "영업 여부")
        String naturalField,

        @Schema(description = """
                점주가 답한 값. **영업 여부(`status`)만 `"true"`·`"false"`** 입니다 — 전화로는
                "지금도 영업하십니까" 한 가지만 묻기 때문에, 휴업과 폐업을 가르는 일은 담당자가 합니다.
                나머지 항목은 들은 값 그대로입니다""", example = "false")
        String value,

        @Schema(description = "이 답이 **가게에 그대로 반영됐는지**. 영업 여부는 휴업과 폐업을 가를 수 없어 "
                + "항상 false 입니다 — 담당자가 판단합니다. 이미 같은 값이었거나 가게 규칙에 맞지 않아도 false 입니다",
                example = "true")
        boolean applied
) {

    /**
     * 저장된 답을 화면용으로 바꾼다. 모르는 항목 키는 버리지 않고 키 그대로 내보낸다.
     *
     * @param answer  통화에서 들은 답
     * @param applied 그 통화가 실제로 가게에 반영한 값들(항목 키 → 값). 없으면 빈 맵
     */
    public static CallAnswerResponse from(CallAnswer answer, Map<String, Object> applied) {
        return CallField.fromApiField(answer.field())
                .map(field -> {
                    String key = field.changeField().key();
                    // 같은 항목이라도 "들은 값" 과 "반영한 값" 이 다를 수 있다(두 번 들었을 때) — 값까지 같아야 반영된 것이다
                    boolean reflected = answer.value() != null && answer.value().equals(applied.get(key));
                    return new CallAnswerResponse(key, field.naturalField(), answer.value(), reflected);
                })
                .orElseGet(() -> new CallAnswerResponse(answer.field(), answer.field(), answer.value(), false));
    }
}
