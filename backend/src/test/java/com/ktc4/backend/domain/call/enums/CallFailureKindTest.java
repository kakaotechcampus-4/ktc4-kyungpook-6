package com.ktc4.backend.domain.call.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallFailureKind} — 전화 API 의 실패 코드를 담당자가 할 일로 바꾼다.
 *
 * <p>여기서 보는 것은 "모르는 코드가 와도 안전한가" 다. 전화 API 의 코드 목록을 아직 받지 못해
 * 대부분이 모르는 코드로 오는데, 그걸 아무 분류에나 넣으면 <b>화면이 엉뚱한 안내를 붙인다</b> —
 * 없는 번호가 아닌데 "번호를 고치세요" 가 뜨는 식이다.
 */
@DisplayName("전화 실패 분류 (CallFailureKind)")
class CallFailureKindTest {

    @Test
    @DisplayName("전화 API 안에서 터진 것은 가게와 무관한 쪽으로 묶는다")
    void 전화_API_문제() {
        // 2026-11-04 에 실제로 받은 코드다. 벨도 울리지 않았고 startedAt 이 비어 있었다
        assertThat(CallFailureKind.of("PROCESSING_ERROR")).isEqualTo(CallFailureKind.OUR_SIDE);
    }

    @Test
    @DisplayName("우리가 기다리다 닫은 것도 가게와 무관하다")
    void 우리가_닫은_것() {
        assertThat(CallFailureKind.of(CallFailureKind.GAVE_UP_WAITING)).isEqualTo(CallFailureKind.OUR_SIDE);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"NO_ANSWER", "BUSY", "처음 보는 코드", "PROCESSING_ERROR_X"})
    @DisplayName("모르는 코드는 UNKNOWN 이다 — 비슷해 보인다고 끼워 맞추지 않는다")
    void 모르는_코드(String reason) {
        assertThat(CallFailureKind.of(reason)).isEqualTo(CallFailureKind.UNKNOWN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("사유가 없어도 터지지 않는다")
    void 사유가_없을_때(String reason) {
        assertThat(CallFailureKind.of(reason)).isEqualTo(CallFailureKind.UNKNOWN);
    }

    @Test
    @DisplayName("앞뒤 공백이 붙어 와도 알아본다")
    void 공백이_붙어_와도() {
        assertThat(CallFailureKind.of("  PROCESSING_ERROR  ")).isEqualTo(CallFailureKind.OUR_SIDE);
    }
}
