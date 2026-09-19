package com.ktc4.backend.domain.business.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 사업자등록 상태조회 API 응답 바디 매핑 전용 DTO.
 *
 * <p>{@code match_cnt}는 1건 이상 매칭됐을 때만 응답에 존재하고 0건 매칭 시에는 필드 자체가
 * 없으므로 {@link Integer}로 nullable 처리한다.
 */
record NtsStatusResponse(
        @JsonProperty("request_cnt") Integer requestCnt,
        @JsonProperty("match_cnt") Integer matchCnt,
        @JsonProperty("status_code") String statusCode,
        @JsonProperty("data") List<Item> data
) {

    record Item(
            @JsonProperty("b_no") String bNo,
            @JsonProperty("b_stt") String bStt,
            @JsonProperty("b_stt_cd") String bSttCd,
            @JsonProperty("tax_type") String taxType,
            @JsonProperty("tax_type_cd") String taxTypeCd,
            @JsonProperty("end_dt") String endDt,
            @JsonProperty("utcc_yn") String utccYn,
            @JsonProperty("tax_type_change_dt") String taxTypeChangeDt,
            @JsonProperty("invoice_apply_dt") String invoiceApplyDt,
            @JsonProperty("rbf_tax_type") String rbfTaxType,
            @JsonProperty("rbf_tax_type_cd") String rbfTaxTypeCd
    ) {
    }
}
