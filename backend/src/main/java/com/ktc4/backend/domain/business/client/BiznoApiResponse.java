package com.ktc4.backend.domain.business.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 비즈노 사업자 검색 API 응답을 그대로 매핑하기 위한 Jackson 전용 record.
 * 도메인 밖에는 노출하지 않고 {@link BiznoApiClient} 내부에서만 사용한다.
 */
record BiznoApiResponse(
        int resultCode,
        String resultMsg,
        String page,
        int maxpage,
        String pagecnt,
        int totalCount,
        List<Item> items) {

    /**
     * {@code items[]} 배열 원소. 원본 JSON 키를 그대로 매핑한다.
     * {@code TaxTypeCd}, {@code EndDt}는 다른 필드와 달리 대문자로 시작하므로
     * {@link JsonProperty}로 명시적으로 매핑한다.
     */
    record Item(
            String company,
            String bno,
            String cno,
            String bsttcd,
            String bstt,
            @JsonProperty("TaxTypeCd") String taxTypeCd,
            String taxtype,
            @JsonProperty("EndDt") String endDt) {
    }
}
