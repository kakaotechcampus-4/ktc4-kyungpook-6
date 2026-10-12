package com.ktc4.backend.domain.auth.dto;

import com.ktc4.backend.domain.store.dto.MenuRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.ktc4.backend.global.util.TextPatterns.HAS_LETTER_OR_DIGIT;
import static com.ktc4.backend.global.util.TextPatterns.NO_INVISIBLE_CHARACTERS;
import static com.ktc4.backend.global.util.TextPatterns.NO_STACKED_MARKS;

/**
 * 가입 신청 때 적는 메뉴 한 개. 이름만 받는다 — 판매 여부는 승인 뒤 가게 관리에서 고르고, 처음에는 판매 중이다.
 *
 * <p>이름 규칙은 가게 관리 저장의 메뉴({@link MenuRequest})와 같다.
 */
public record SignupMenuRequest(
        @Schema(description = "메뉴 이름 (50자 이하)", example = "김치찌개")
        @NotBlank(message = "메뉴 이름을 입력해 주세요")
        @Size(max = MenuRequest.MAX_NAME_LENGTH, message = "메뉴 이름은 {max}자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "메뉴 이름에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "메뉴 이름에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "메뉴 이름에 겹쳐 쓴 부호가 너무 많습니다")
        String name
) {
}
