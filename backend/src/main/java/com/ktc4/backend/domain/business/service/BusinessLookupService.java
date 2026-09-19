package com.ktc4.backend.domain.business.service;

import com.ktc4.backend.domain.business.client.BiznoClient;
import com.ktc4.backend.domain.business.client.NtsClient;
import com.ktc4.backend.domain.business.dto.BiznoBusinessCandidate;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 비즈노·국세청 외부 API 클라이언트를 그대로 호출만 하는 얇은 위임(pass-through) 서비스.
 *
 * <p>비즈노 검색 결과를 국세청 조회 입력으로 이어붙이는 로직이나, 사업자등록번호를
 * 정규화·비교하는 로직은 이 서비스의 책임이 아니다. 각 메서드는 대응하는 클라이언트를
 * 호출해 받은 결과를 가공 없이 그대로 반환한다.
 */
@Service
@RequiredArgsConstructor
public class BusinessLookupService {

    private final BiznoClient biznoClient;
    private final NtsClient ntsClient;

    /**
     * 비즈노 사업자 검색 API를 상호명으로 호출해 사업자등록번호 후보 목록을 그대로 반환한다.
     *
     * @param keyword 검색할 상호명(가게 이름)
     * @return 비즈노 API가 반환한 사업자 후보 목록(원본 그대로, 필터링 없음)
     */
    public List<BiznoBusinessCandidate> searchBizno(String keyword) {
        return biznoClient.search(keyword);
    }

    /**
     * 국세청 사업자등록 상태조회 API를 호출하여 사업자등록번호 목록의 상태를 그대로 반환한다.
     *
     * <p>매칭되지 않은 사업자등록번호도 결과 목록에서 빠지지 않고, 상태 관련 필드가 빈 문자열로
     * 채워진 채 포함된다(자세한 계약과 100건 제한·1:1 대응 예외 상황은
     * {@link com.ktc4.backend.domain.business.client.NtsClient} 참고).
     *
     * @param bizNos 조회할 사업자등록번호 목록 (하이픈 포함 여부 무관, 최대 100건 — 넘으면 예외)
     * @return 정상적인 경우 입력 목록과 1:1로 대응하는 사업자등록 상태 목록(원본 그대로)
     */
    public List<NtsBusinessStatus> getNtsStatuses(List<String> bizNos) {
        return ntsClient.getStatuses(bizNos);
    }
}
