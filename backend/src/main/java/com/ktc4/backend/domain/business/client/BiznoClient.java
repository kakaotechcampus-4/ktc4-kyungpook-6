package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.BiznoBusinessCandidate;
import java.util.List;

public interface BiznoClient {

    /**
     * 비즈노 사업자 검색 API를 호출해 사업자 후보 목록을 조회한다.
     *
     * <p>무료 API 기준으로 {@code keyword}엔 상호명뿐 아니라 사업자등록번호(하이픈 유무 무관),
     * 법인등록번호(하이픈 유무 무관)도 넣을 수 있다(실제 발급받은 API 키로 세 가지 방식 모두
     * 직접 호출해 확인함). 다만 전화번호·대표자명 검색은 유료 API 전용이라 이 키로는 동작하지
     * 않는다.
     *
     * <p>상호명으로 검색할 경우 정확한 상호명이 아니면 매칭이 안 될 수 있고, 상호명과 무관한
     * 후보가 채워져서 올 수도 있다. 이런 결과는 걸러내지 않고 API 응답을 그대로 반환하므로,
     * 관련성 판단이나 재시도 로직은 호출하는 쪽에서 처리해야 한다. 단, 요청한 {@code pagecnt}보다
     * 실제 매칭 건수가 적으면 비즈노가 남는 자리를 {@code null}로 채워서 보내는데(실측 확인됨),
     * 이 {@code null} 항목만은 결과에 담을 수 없는 값이라 걸러내고 반환한다.
     *
     * @param keyword 검색어 — 상호명, 사업자등록번호, 법인등록번호 중 하나
     * @return 비즈노 API가 반환한 사업자 후보 목록(내용 필터링 없음, null 패딩만 제거). 결과가 없으면 빈 리스트를 반환한다.
     */
    List<BiznoBusinessCandidate> search(String keyword);
}
