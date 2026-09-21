package com.ktc4.backend.domain.store.ntscheck.scheduler;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.service.StoreNtsCheckService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// DB·국세청·저장 서비스를 모두 가짜(Mockito)로 바꿔, 배치가 "누구를 어떻게 묶어서 몇 번 조회하고
// 실패를 어디까지 격리하는지"만 검증한다.
@ExtendWith(MockitoExtension.class)
@DisplayName("NtsCheckScheduler.checkAllStores")
class NtsCheckSchedulerTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private BusinessLookupService businessLookupService;

    @Mock
    private StoreNtsCheckService storeNtsCheckService;

    @InjectMocks
    private NtsCheckScheduler ntsCheckScheduler;

    @Captor
    private ArgumentCaptor<List<String>> bizNosCaptor;

    @Captor
    private ArgumentCaptor<List<Store>> storesCaptor;

    @Captor
    private ArgumentCaptor<Map<String, BusinessStatus>> statusesCaptor;

    @Captor
    private ArgumentCaptor<LocalDateTime> attemptedAtCaptor;

    private static Store store(long storeId, String bizNo) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .build();
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    // 국세청 원본 응답 한 건. 우리가 쓰는 세 필드만 채운다.
    private static NtsBusinessStatus raw(String bNo, String bSttCd) {
        return new NtsBusinessStatus(bNo, "", bSttCd, "", "", "", "", "", "", "", "");
    }

    private static String bizNo(int index) {
        return String.format("%010d", index);
    }

    @Test
    @DisplayName("사업자번호가 없거나 형식이 틀린 가게는 조회하지 않고 NO_BIZ_NO 로 기록한다")
    void marksStoresWithoutUsableBizNo() {
        when(storeRepository.findAll()).thenReturn(List.of(
                store(1L, null),
                store(2L, "12345"),
                store(3L, "사업자번호없음")));

        ntsCheckScheduler.checkAllStores();

        verify(storeNtsCheckService).markNoBizNo(storesCaptor.capture(), any(LocalDateTime.class));
        assertThat(storesCaptor.getValue()).extracting(Store::getStoreId).containsExactly(1L, 2L, 3L);
        verifyNoInteractions(businessLookupService);
        verify(storeNtsCheckService, never()).markChecked(anyList(), anyMap(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("가게가 하나도 없으면 국세청 API 를 호출하지 않는다")
    void doesNothingWhenNoStores() {
        when(storeRepository.findAll()).thenReturn(List.of());

        ntsCheckScheduler.checkAllStores();

        verifyNoInteractions(businessLookupService);
    }

    @Test
    @DisplayName("사업자번호 250건은 100/100/50 으로 나눠 세 번 조회한다")
    void splitsLookupIntoChunksOfHundred() {
        List<Store> stores = IntStream.rangeClosed(1, 250)
                .mapToObj(i -> store(i, bizNo(i)))
                .toList();
        when(storeRepository.findAll()).thenReturn(stores);
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of());

        ntsCheckScheduler.checkAllStores();

        verify(businessLookupService, times(3)).getNtsStatuses(bizNosCaptor.capture());
        assertThat(bizNosCaptor.getAllValues()).extracting(List::size).containsExactly(100, 100, 50);
        assertThat(bizNosCaptor.getAllValues().stream().flatMap(List::stream).toList())
                .hasSize(250)
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, 250).mapToObj(NtsCheckSchedulerTest::bizNo).toList());
    }

    @Test
    @DisplayName("같은 사업자번호를 쓰는 가게가 여럿이면 조회는 한 번만 하고 두 가게 모두 기록한다")
    void looksUpSharedBizNoOnce() {
        when(storeRepository.findAll()).thenReturn(List.of(
                store(1L, "123-45-67890"),
                store(2L, "1234567890")));
        when(businessLookupService.getNtsStatuses(anyList()))
                .thenReturn(List.of(raw("1234567890", "01")));

        ntsCheckScheduler.checkAllStores();

        verify(businessLookupService).getNtsStatuses(bizNosCaptor.capture());
        assertThat(bizNosCaptor.getValue()).containsExactly("1234567890");

        verify(storeNtsCheckService).markChecked(storesCaptor.capture(), statusesCaptor.capture(),
                any(LocalDateTime.class));
        assertThat(storesCaptor.getValue()).extracting(Store::getStoreId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(statusesCaptor.getValue()).containsOnlyKeys("1234567890");
        assertThat(statusesCaptor.getValue().get("1234567890").state()).isEqualTo(BusinessState.ACTIVE);
    }

    @Test
    @DisplayName("한 묶음의 조회가 실패해도 그 묶음만 UNCONFIRMED 로 기록하고 나머지는 계속 조회한다")
    void isolatesFailedChunk() {
        List<Store> stores = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> store(i, bizNo(i)))
                .toList();
        when(storeRepository.findAll()).thenReturn(stores);
        when(businessLookupService.getNtsStatuses(anyList()))
                .thenThrow(new CustomException(ErrorCode.NTS_API_ERROR))
                .thenReturn(List.of());

        ntsCheckScheduler.checkAllStores();

        verify(businessLookupService, times(2)).getNtsStatuses(bizNosCaptor.capture());
        Set<String> failedBizNos = Set.copyOf(bizNosCaptor.getAllValues().get(0));

        verify(storeNtsCheckService).markUnconfirmed(storesCaptor.capture(), attemptedAtCaptor.capture());
        assertThat(storesCaptor.getValue()).hasSize(failedBizNos.size());
        assertThat(storesCaptor.getValue()).allSatisfy(store ->
                assertThat(failedBizNos).contains(store.getBizNo()));

        ArgumentCaptor<List<Store>> checkedCaptor = ArgumentCaptor.forClass(List.class);
        verify(storeNtsCheckService).markChecked(checkedCaptor.capture(), anyMap(), attemptedAtCaptor.capture());
        assertThat(checkedCaptor.getValue()).hasSize(101 - failedBizNos.size());

        // 한 번의 배치 실행은 모든 기록에 같은 시각을 쓴다 — 나중에 "이번 회차에 무엇이 처리됐는지"를
        // 시각으로 묶어 볼 수 있어야 하기 때문.
        assertThat(attemptedAtCaptor.getAllValues()).containsOnly(attemptedAtCaptor.getValue());
    }

    @Test
    @DisplayName("결과 저장이 실패해도 다음 묶음 조회는 계속한다")
    void continuesAfterSaveFailure() {
        List<Store> stores = IntStream.rangeClosed(1, 101)
                .mapToObj(i -> store(i, bizNo(i)))
                .toList();
        when(storeRepository.findAll()).thenReturn(stores);
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("저장 실패"))
                .when(storeNtsCheckService).markChecked(anyList(), anyMap(), any(LocalDateTime.class));

        ntsCheckScheduler.checkAllStores();

        // 첫 묶음 저장이 터져도 두 번째 묶음까지 조회가 이어져야 한다
        verify(businessLookupService, times(2)).getNtsStatuses(anyList());
        verify(storeNtsCheckService, times(2)).markChecked(anyList(), anyMap(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("사업자번호 없는 가게 저장이 실패해도 나머지 가게는 조회한다")
    void continuesAfterNoBizNoSaveFailure() {
        when(storeRepository.findAll()).thenReturn(List.of(
                store(1L, null),
                store(2L, "1111111111")));
        doThrow(new DataIntegrityViolationException("저장 실패"))
                .when(storeNtsCheckService).markNoBizNo(anyList(), any(LocalDateTime.class));
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of());

        ntsCheckScheduler.checkAllStores();

        verify(businessLookupService).getNtsStatuses(bizNosCaptor.capture());
        assertThat(bizNosCaptor.getValue()).containsExactly("1111111111");
    }

    @Test
    @DisplayName("응답에 없는 사업자번호와 해석할 수 없는 응답은 상태 목록에서 빠진다")
    void skipsMissingAndUnparsableResponses() {
        when(storeRepository.findAll()).thenReturn(List.of(
                store(1L, "1111111111"),
                store(2L, "2222222222"),
                store(3L, "3333333333")));
        // 1번은 정상, 2번은 알 수 없는 상태코드, 3번은 응답에 아예 없음
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of(
                raw("1111111111", "01"),
                raw("2222222222", "99")));

        ntsCheckScheduler.checkAllStores();

        verify(storeNtsCheckService).markChecked(anyList(), statusesCaptor.capture(), any(LocalDateTime.class));
        assertThat(statusesCaptor.getValue()).containsOnlyKeys("1111111111");
    }

    @Test
    @DisplayName("요청하지 않은 사업자번호가 응답에 섞여 오면 무시한다")
    void ignoresUnrequestedBizNoInResponse() {
        when(storeRepository.findAll()).thenReturn(List.of(store(1L, "1111111111")));
        when(businessLookupService.getNtsStatuses(anyList())).thenReturn(List.of(
                raw("1111111111", "01"),
                raw("9999999999", "01"),
                raw(null, "01")));

        ntsCheckScheduler.checkAllStores();

        verify(storeNtsCheckService).markChecked(anyList(), statusesCaptor.capture(), any(LocalDateTime.class));
        assertThat(statusesCaptor.getValue()).containsOnlyKeys("1111111111");
    }
}
