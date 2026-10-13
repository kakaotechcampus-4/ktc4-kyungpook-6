package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import static com.ktc4.backend.global.util.TextPatterns.HAS_LETTER_OR_DIGIT;
import static com.ktc4.backend.global.util.TextPatterns.NO_INVISIBLE_CHARACTERS;
import static com.ktc4.backend.global.util.TextPatterns.NO_STACKED_MARKS;

/**
 * 가게 관리에서 저장하는 메뉴 한 개.
 *
 * <p>{@code menuId} 가 있으면 그 메뉴를 고치고, 없으면 새 메뉴다. 메뉴 이름은 관리자 화면과 아동 앱에 그대로 보이므로
 * 상호명과 같은 글자 규칙({@link com.ktc4.backend.global.util.TextPatterns})을 쓴다.
 */
public record MenuRequest(
        @Schema(description = "메뉴 ID. 이미 있는 메뉴를 고칠 때 싣고, 새 메뉴는 비운다", example = "3")
        @Positive(message = "메뉴 ID 가 올바르지 않습니다")
        Long menuId,

        @Schema(description = "메뉴 이름 (50자 이하)", example = "김치찌개")
        @NotBlank(message = "메뉴 이름을 입력해 주세요")
        @Size(max = MAX_NAME_LENGTH, message = "메뉴 이름은 {max}자를 넘을 수 없습니다")
        @Pattern(regexp = HAS_LETTER_OR_DIGIT, message = "메뉴 이름에는 글자나 숫자가 하나 이상 있어야 합니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "메뉴 이름에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        @Pattern(regexp = NO_STACKED_MARKS, message = "메뉴 이름에 겹쳐 쓴 부호가 너무 많습니다")
        String name,

        @Schema(description = "판매 중인지. 끄면 아동 앱에서 주문할 수 없다", example = "true")
        @NotNull(message = "판매 여부를 골라 주세요")
        Boolean onSale
) {
    /** 한 가게의 메뉴 수 상한. */
    public static final int MAX_COUNT = 50;
    /** 메뉴 이름 길이 상한. */
    public static final int MAX_NAME_LENGTH = 50;
}
