package com.ktc4.backend.domain.business.service;

import com.ktc4.backend.domain.business.client.BiznoClient;
import com.ktc4.backend.domain.business.client.NtsClient;
import com.ktc4.backend.domain.business.dto.BiznoBusinessCandidate;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BusinessLookupService} 단위 테스트.
 *
 * <p>이 서비스는 비즈노/국세청 클라이언트를 그대로 호출만 하는 얇은 위임체이므로,
 * (1) 클라이언트가 올바른 인자로 호출되는지, (2) 클라이언트가 반환한 값이 가공 없이
 * 그대로 반환되는지, (3) 두 클라이언트를 잇는 로직이 없는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BusinessLookupServiceTest {

    @Mock
    private BiznoClient biznoClient;

    @Mock
    private NtsClient ntsClient;

    private BusinessLookupService businessLookupService;

    @BeforeEach
    void setUp() {
        businessLookupService = new BusinessLookupService(biznoClient, ntsClient);
    }

    @Test
    void 비즈노_검색을_요청받은_키워드_그대로_클라이언트에_전달한다() {
        String keyword = "성심당";
        List<BiznoBusinessCandidate> expected = List.of(
                new BiznoBusinessCandidate("성심당", "305-81-48738", "", "01", "계속사업자", "", "부가가치세 일반과세자", "")
        );
        when(biznoClient.search(keyword)).thenReturn(expected);

        List<BiznoBusinessCandidate> result = businessLookupService.searchBizno(keyword);

        verify(biznoClient).search(keyword);
        verifyNoMoreInteractions(biznoClient, ntsClient);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void 비즈노_검색_결과가_빈_리스트여도_가공_없이_그대로_반환한다() {
        String keyword = "존재하지않는가게이름";
        when(biznoClient.search(keyword)).thenReturn(List.of());

        List<BiznoBusinessCandidate> result = businessLookupService.searchBizno(keyword);

        assertThat(result).isEmpty();
    }

    @Test
    void 국세청_상태조회를_요청받은_사업자번호_목록_그대로_클라이언트에_전달한다() {
        List<String> bizNos = List.of("305-81-48738", "0000000000");
        List<NtsBusinessStatus> expected = List.of(
                new NtsBusinessStatus("3058148738", "계속사업자", "01", "부가가치세 일반과세자", "01", "", "N", "", "20120401", "", ""),
                new NtsBusinessStatus("0000000000", "", "", "국세청에 등록되지 않은 사업자등록번호이거나 확인할 수 없습니다.", "", "", "", "", "", "", "")
        );
        when(ntsClient.getStatuses(bizNos)).thenReturn(expected);

        List<NtsBusinessStatus> result = businessLookupService.getNtsStatuses(bizNos);

        verify(ntsClient).getStatuses(bizNos);
        verifyNoMoreInteractions(biznoClient, ntsClient);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void 비즈노_검색과_국세청_조회_사이에_이어붙이는_로직이_없다() {
        List<String> bizNos = List.of("3058148738");
        when(ntsClient.getStatuses(bizNos)).thenReturn(List.of());

        businessLookupService.getNtsStatuses(bizNos);

        verify(ntsClient).getStatuses(bizNos);
        verifyNoMoreInteractions(biznoClient);
    }
}
