package com.ktc4.backend.domain.store.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가게 상세 응답의 JSON 모양. 조회 API 는 아직 501 이라 HTTP 로는 볼 수 없지만, 점주 앱은 이 모양으로 화면을 만든다.
 * 앱이 쓰는 Jackson 설정(Spring Boot 기본)으로 직렬화해 필드 이름과 값 형식을 고정한다.
 */
@JsonTest
@DisplayName("가게 상세 응답 JSON")
class StoreDetailResponseJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("필드 이름과 값 형식 — 시간은 HH:mm 문자열, 요일은 MON~SUN")
    void fieldNamesAndFormats() throws Exception {
        StoreDetailResponse response = new StoreDetailResponse(12L, "예시분식", "대구광역시 북구 대학로 80", "053-123-4567",
                List.of(new BusinessHourResponse(BusinessDay.MON, "00:00", "24:00")),
                List.of(new MenuResponse(3L, "김치찌개", false, null),
                        new MenuResponse(4L, "라면", true, "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("storeId", "name", "addressRoad", "phone", "businessHours", "menus");
        assertThat(json.get("businessHours").get(0).toString())
                .isEqualTo("{\"day\":\"MON\",\"open\":\"00:00\",\"close\":\"24:00\"}");
        assertThat(json.get("menus").get(0).toString())
                .isEqualTo("{\"menuId\":3,\"name\":\"김치찌개\",\"onSale\":false,\"photoFileId\":null}");
        // 사진이 없어도 칸은 빠지지 않고 null 로 나간다 — 앱이 "칸이 없음"과 "사진 없음"을 따로 다루지 않게
        assertThat(json.get("menus").get(1).get("photoFileId").asText()).isEqualTo("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
    }

    @ParameterizedTest
    @EnumSource(BusinessDay.class)
    @DisplayName("요일은 이름 그대로 나가고 그대로 읽힌다 — 9/29 프론트 합의 값(MON~SUN)")
    void businessDayRoundTrip(BusinessDay day) throws Exception {
        String json = objectMapper.writeValueAsString(day);

        assertThat(json).isEqualTo("\"" + day.name() + "\"");
        assertThat(day.name()).hasSize(3);
        assertThat(objectMapper.readValue(json, BusinessDay.class)).isEqualTo(day);
    }
}
