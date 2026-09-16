package com.ktc4.backend.domain.business.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 사업자등록 상태조회 API 요청 바디 매핑 전용 DTO.
 */
record NtsStatusRequest(
        @JsonProperty("b_no") List<String> bNo
) {
}
