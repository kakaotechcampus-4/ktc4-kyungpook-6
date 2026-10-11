package com.ktc4.backend.domain.member.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NameMatch")
class NameMatchTest {

    @ParameterizedTest(name = "[{index}] 신청서 \"{0}\" / 가게 \"{1}\" → {2}")
    @CsvSource({
            "예시분식, 예시분식, EXACT",
            "예시분식, 대구예시분식2호점, PARTIAL",
            "예시분식, 샘플카페, NONE",
            // 가게 이름이 신청서 상호명보다 짧으면 겹침이 아니다 — 후보를 찾는 질의와 같은 방향이다
            "대구예시분식2호점, 예시분식, NONE"
    })
    @DisplayName("정규화한 이름이 같으면 EXACT, 가게 이름에 들어 있으면 PARTIAL, 아니면 NONE")
    void classifies(String applicantName, String storeName, NameMatch expected) {
        assertThat(NameMatch.of(applicantName, storeName)).isEqualTo(expected);
    }

    @Test
    @DisplayName("신청서 상호명이 한 글자 이하면 겹쳐 보여도 NONE — 이름으로는 후보를 찾지 않은 경우다")
    void shortApplicantNameIsNone() {
        assertThat(NameMatch.of("밥", "밥")).isEqualTo(NameMatch.NONE);
        assertThat(NameMatch.of("밥", "엄마밥상")).isEqualTo(NameMatch.NONE);
        assertThat(NameMatch.of("", "예시분식")).isEqualTo(NameMatch.NONE);
    }

    @Test
    @DisplayName("어느 한쪽이 없으면 NONE")
    void nullIsNone() {
        assertThat(NameMatch.of(null, "예시분식")).isEqualTo(NameMatch.NONE);
        assertThat(NameMatch.of("예시분식", null)).isEqualTo(NameMatch.NONE);
    }
}
