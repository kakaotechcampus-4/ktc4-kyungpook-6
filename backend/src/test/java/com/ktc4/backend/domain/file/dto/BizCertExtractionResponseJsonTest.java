package com.ktc4.backend.domain.file.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.auth.dto.OwnerSignupRequest;
import com.ktc4.backend.domain.file.enums.NtsVerification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사업자등록증 읽기 응답의 JSON 모양. API 는 아직 501 이라 HTTP 로는 볼 수 없지만, 점주 앱은 이 모양으로 가입 form 을 채운다.
 * 앱이 쓰는 Jackson 설정(Spring Boot 기본)으로 직렬화해 필드 이름과 값 형식을 고정한다.
 */
@JsonTest
@DisplayName("사업자등록증 읽기 응답 JSON")
class BizCertExtractionResponseJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("필드 이름은 가입 신청 칸 이름과 같고, 개업일은 yyyy-MM-dd 문자열이다")
    void fieldNamesAndFormats() throws Exception {
        BizCertExtractionResponse response = new BizCertExtractionResponse("1234567890", "예시분식", "홍길동",
                "대구광역시 북구 대학로 80", LocalDate.of(2019, 3, 2), null, "음식점업", "한식, 분식",
                NtsVerification.VERIFIED);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("bizNo", "storeName", "representativeName", "storeAddress",
                        "openDate", "corpName", "businessType", "businessItem", "ntsVerification");
        assertThat(json.get("openDate").asText()).isEqualTo("2019-03-02");
        assertThat(json.get("ntsVerification").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("못 읽은 칸도 빠지지 않고 null 로 나간다 — 앱이 '칸이 없음'과 '못 읽음'을 따로 다루지 않게")
    void unreadFieldsStayAsNull() throws Exception {
        BizCertExtractionResponse nothingRead = new BizCertExtractionResponse(null, null, null, null, null, null,
                null, null, NtsVerification.UNVERIFIED);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(nothingRead));

        assertThat(json.size()).isEqualTo(9);
        json.properties().stream()
                .filter(field -> !field.getKey().equals("ntsVerification"))
                .forEach(field -> assertThat(field.getValue().isNull()).as(field.getKey()).isTrue());
        assertThat(json.get("ntsVerification").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("국세청 확인 결과는 비울 수 없다 — 확인 못 했으면 UNVERIFIED")
    void ntsVerificationIsRequired() {
        assertThatThrownBy(() -> new BizCertExtractionResponse("1234567890", null, null, null, null, null, null,
                null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("가입 신청에 이미 있는 값은 가입 신청과 같은 칸 이름이다 — 한쪽만 이름을 바꾸면 앱이 옮겨 담지 못한다")
    void sharedFieldsMatchSignupRequest() {
        Set<String> signupFields = Arrays.stream(OwnerSignupRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(signupFields).contains("bizNo", "storeName", "representativeName", "storeAddress");
    }
}
