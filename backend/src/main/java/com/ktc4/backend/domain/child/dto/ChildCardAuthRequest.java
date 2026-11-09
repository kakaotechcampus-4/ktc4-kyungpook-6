package com.ktc4.backend.domain.child.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 아동 급식카드 인증 요청.
 *
 * <p>검사는 형식(숫자 16자리)뿐이다 — 실제 급식카드인지는 확인하지 않는다(카드사 연동이 없는 시연용).
 * {@code [0-9]} 로 적어 전각·아라비아 숫자 같은 다른 문자의 숫자는 받지 않는다.
 */
public record ChildCardAuthRequest(
        @Schema(description = "급식카드 번호 16자리. 숫자 사이에 공백·하이픈이 하나씩 있어도 된다", example = "1234 5678 1234 5678")
        @NotNull(message = "카드 번호를 입력해 주세요")
        @Pattern(regexp = "^[0-9](?:[ -]?[0-9]){15}$", message = "카드 번호는 숫자 16자리여야 합니다")
        String cardNumber
) {
    // 로그에 요청 객체가 찍혀도 카드 번호가 새지 않게 한다. record 기본 toString 은 모든 필드를 출력한다.
    @Override
    public String toString() {
        return "ChildCardAuthRequest[cardNumber=****]";
    }
}
