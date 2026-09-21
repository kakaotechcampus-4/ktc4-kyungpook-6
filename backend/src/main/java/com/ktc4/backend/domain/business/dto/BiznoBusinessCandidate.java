package com.ktc4.backend.domain.business.dto;

/**
 * 비즈노 사업자 검색 API가 반환한 사업자 후보 1건을 원본 그대로(raw) 담는 DTO.
 * 정규화나 필터링 없이 API 응답 필드를 그대로 옮겨 담는다.
 *
 * <p>실제 API 호출로 검증한 바로는 값이 없을 때 빈 문자열({@code ""})로 채워져 왔지만, 이는
 * 관측된 샘플일 뿐 비즈노가 문서로 보장하는 계약은 아니다. 호출하는 쪽에서 {@code null} 가능성까지
 * 방어적으로 처리하는 것을 권장한다.
 *
 * <p>필드명 표기가 서로 다른 이유(예: {@code bsttcd}는 소문자, {@code taxTypeCd}는 캐멀케이스):
 * 비즈노 원본 JSON 필드명을 그대로 보존한 것이다({@code bsttcd}는 원본도 소문자,
 * {@code TaxTypeCd}는 원본이 대문자로 시작해 캐멀케이스로 옮김). 오타가 아니다.
 */
public record BiznoBusinessCandidate(
        String company,
        String bno,
        String cno,
        String bsttcd,
        String bstt,
        String taxTypeCd,
        String taxtype,
        String endDt) {
}
