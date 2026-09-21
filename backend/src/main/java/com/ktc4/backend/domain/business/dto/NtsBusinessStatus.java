package com.ktc4.backend.domain.business.dto;

/**
 * 국세청 사업자등록 상태조회 API 응답을 가공 없이 원본(raw) 그대로 담는 DTO.
 *
 * <p>필드 값의 정규화나 비교 판단은 이 DTO의 책임이 아니며, 국세청 API가 내려준 문자열을
 * 그대로 보관한다.
 *
 * <p>실제 API 호출로 검증한 바로는 사업자등록번호가 매칭되지 않은 경우 상태 관련 필드가 빈
 * 문자열({@code ""})로 채워져 왔지만, 이는 관측된 샘플일 뿐 국세청이 문서로 보장하는 계약은
 * 아니다. 호출하는 쪽에서 {@code null} 가능성까지 방어적으로 처리하는 것을 권장한다.
 */
public record NtsBusinessStatus(
        String bNo,
        String bStt,
        String bSttCd,
        String taxType,
        String taxTypeCd,
        String endDt,
        String utccYn,
        String taxTypeChangeDt,
        String invoiceApplyDt,
        String rbfTaxType,
        String rbfTaxTypeCd
) {
}
