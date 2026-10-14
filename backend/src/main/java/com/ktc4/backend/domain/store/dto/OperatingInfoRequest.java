package com.ktc4.backend.domain.store.dto;

import com.ktc4.backend.domain.store.validation.NonOverlappingBusinessHours;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

import static com.ktc4.backend.global.util.TextPatterns.NO_INVISIBLE_CHARACTERS;

/**
 * 가게 관리 화면의 "저장" — 가게 전화·영업시간·메뉴를 한 번에 통째로 바꾼다. 전부 반영되거나 하나도 반영되지 않는다.
 *
 * <p>통째로 바꾸는 저장이라 세 칸을 모두 보내야 한다. 빠진 칸을 "그대로 둔다"로 읽으면, 앱이 실수로 칸을 빠뜨렸을 때
 * 아무 오류 없이 이전 값이 남는다.
 *
 * <p>영업시간과 메뉴는 가입 때 필수였던 값이라 저장으로 비울 수 없다(각각 한 줄·한 개 이상). 가게 전화도 같다.
 */
public record OperatingInfoRequest(
        @Schema(description = "가게 전화번호 (20자 이하). 매장 유선 번호도 된다. 가입 신청의 storePhone 과 같은 값이다",
                example = "053-123-4567")
        @NotBlank(message = "가게 전화번호를 입력해 주세요")
        @Size(max = 20, message = "가게 전화번호는 {max}자를 넘을 수 없습니다")
        @Pattern(regexp = NO_INVISIBLE_CHARACTERS, message = "가게 전화번호에 줄바꿈이나 보이지 않는 글자를 넣을 수 없습니다")
        String phone,

        @ArraySchema(arraySchema = @Schema(description = "영업시간 (1 ~ 21줄). 보낸 목록으로 통째로 바뀐다"),
                minItems = 1, maxItems = BusinessHourRequest.MAX_LINES)
        @NotNull(message = "영업시간을 보내 주세요")
        @Size(min = 1, max = BusinessHourRequest.MAX_LINES, message = "영업시간은 {min} ~ {max}줄이어야 합니다")
        @NonOverlappingBusinessHours
        List<@Valid @NotNull(message = "빈 영업시간 줄이 있습니다") BusinessHourRequest> businessHours,

        @ArraySchema(arraySchema = @Schema(description = """
                메뉴 (1 ~ 50개). menuId 가 있으면 그 메뉴를 고치고, 없으면 새로 만들고, 목록에서 빠진 메뉴는 삭제된다.
                다른 가게의 menuId 나 같은 menuId 를 두 번 보내면 400 이다"""),
                minItems = 1, maxItems = MenuRequest.MAX_COUNT)
        @NotNull(message = "메뉴를 보내 주세요")
        @Size(min = 1, max = MenuRequest.MAX_COUNT, message = "메뉴는 {min} ~ {max}개여야 합니다")
        List<@Valid @NotNull(message = "빈 메뉴가 있습니다") MenuRequest> menus
) {
}
