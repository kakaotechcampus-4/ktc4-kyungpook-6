package com.ktc4.backend.domain.store.ntscheck.service;

import com.ktc4.backend.domain.business.dto.BusinessStatus;
import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsChangeRepository;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// DB 를 가짜(Mockito)로 바꿔 "무엇을 어떻게 갱신하고, 언제 이력을 남기는지"만 검증한다.
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreNtsCheckService")
class StoreNtsCheckServiceTest {

    private static final LocalDateTime ATTEMPTED_AT = LocalDateTime.of(2026, 9, 18, 3, 0);
    private static final LocalDateTime EARLIER = ATTEMPTED_AT.minusDays(1);

    @Mock
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Mock
    private StoreNtsChangeRepository storeNtsChangeRepository;

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreNtsCheckService storeNtsCheckService;

    @Captor
    private ArgumentCaptor<List<StoreNtsCheck>> checksCaptor;

    @Captor
    private ArgumentCaptor<List<StoreNtsChange>> changesCaptor;

    private static Store store(long storeId, String bizNo) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .build();
        // storeId 는 DB 가 채우는 값이라 builder 에 없어서 직접 넣는다
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    // 이전 배치에서 이미 확인에 성공해 둔 기록
    private static StoreNtsCheck previouslyConfirmed(Store store, BusinessState state) {
        StoreNtsCheck check = StoreNtsCheck.builder()
                .store(store)
                .bizNo("1234567890")
                .checkResult(NtsLookupResult.CONFIRMED)
                .ntsState(state)
                .lastAttemptAt(EARLIER)
                .lastSuccessAt(EARLIER)
                .build();
        ReflectionTestUtils.setField(check, "storeId", store.getStoreId());
        return check;
    }

    @Test
    @DisplayName("처음 확인한 가게는 기록을 새로 만들고, 이전 상태가 없는 이력을 남긴다")
    void createsCheckAndChangeOnFirstConfirmation() {
        Store store = store(1L, "123-45-67890");
        when(storeNtsCheckRepository.findAllById(anyList())).thenReturn(List.of());
        // 새 기록은 detached 객체 대신 영속 상태의 참조로 만든다 (findOrCreate Javadoc 참고)
        when(storeRepository.getReferenceById(1L)).thenReturn(store);

        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null)),
                ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getStore()).isSameAs(store);
        assertThat(saved.getBizNo()).isEqualTo("1234567890");
        assertThat(saved.getCheckResult()).isEqualTo(NtsLookupResult.CONFIRMED);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(saved.getLastAttemptAt()).isEqualTo(ATTEMPTED_AT);
        assertThat(saved.getLastSuccessAt()).isEqualTo(ATTEMPTED_AT);

        verify(storeNtsChangeRepository).saveAll(changesCaptor.capture());
        StoreNtsChange change = changesCaptor.getValue().get(0);
        assertThat(change.getStore()).isSameAs(store);
        assertThat(change.getFromState()).isNull();
        assertThat(change.getToState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(change.getDetectedAt()).isEqualTo(ATTEMPTED_AT);
    }

    @Test
    @DisplayName("국세청 상태가 이전과 같으면 이력을 남기지 않고 확인 시각만 갱신한다")
    void doesNotRecordChangeWhenStateUnchanged() {
        Store store = store(1L, "1234567890");
        when(storeNtsCheckRepository.findAllById(anyList()))
                .thenReturn(List.of(previouslyConfirmed(store, BusinessState.ACTIVE)));

        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null)),
                ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(saved.getLastAttemptAt()).isEqualTo(ATTEMPTED_AT);
        assertThat(saved.getLastSuccessAt()).isEqualTo(ATTEMPTED_AT);

        verify(storeNtsChangeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("국세청 상태가 달라지면 이전→현재 이력을 남기고 폐업일도 갱신한다")
    void recordsChangeWhenStateDiffers() {
        Store store = store(1L, "1234567890");
        when(storeNtsCheckRepository.findAllById(anyList()))
                .thenReturn(List.of(previouslyConfirmed(store, BusinessState.ACTIVE)));

        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890",
                        new BusinessStatus("1234567890", BusinessState.CLOSED, LocalDate.of(2026, 3, 1))),
                ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.CLOSED);
        assertThat(saved.getNtsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));

        verify(storeNtsChangeRepository).saveAll(changesCaptor.capture());
        StoreNtsChange change = changesCaptor.getValue().get(0);
        assertThat(change.getFromState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(change.getToState()).isEqualTo(BusinessState.CLOSED);
    }

    @Test
    @DisplayName("폐업이었다가 다시 영업 상태로 확인되면 폐업일을 지운다")
    void clearsClosedAtWhenNoLongerClosed() {
        Store store = store(1L, "1234567890");
        StoreNtsCheck closed = previouslyConfirmed(store, BusinessState.CLOSED);
        ReflectionTestUtils.setField(closed, "ntsClosedAt", LocalDate.of(2026, 3, 1));
        when(storeNtsCheckRepository.findAllById(anyList())).thenReturn(List.of(closed));

        storeNtsCheckService.markChecked(
                List.of(store),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null)),
                ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(saved.getNtsClosedAt()).isNull();
    }

    @Test
    @DisplayName("응답에 없는 가게는 UNCONFIRMED 로 기록하되 이전 국세청 상태와 성공 시각은 보존한다")
    void keepsPreviousStateWhenMissingFromResponse() {
        Store store = store(1L, "1234567890");
        when(storeNtsCheckRepository.findAllById(anyList()))
                .thenReturn(List.of(previouslyConfirmed(store, BusinessState.CLOSED)));

        storeNtsCheckService.markChecked(List.of(store), Map.of(), ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getCheckResult()).isEqualTo(NtsLookupResult.UNCONFIRMED);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.CLOSED);
        assertThat(saved.getLastSuccessAt()).isEqualTo(EARLIER);
        assertThat(saved.getLastAttemptAt()).isEqualTo(ATTEMPTED_AT);

        verify(storeNtsChangeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("한 번의 호출에서 확인된 가게와 확인 못 한 가게를 각각 다르게 기록한다")
    void recordsConfirmedAndUnconfirmedTogether() {
        Store confirmed = store(1L, "1234567890");
        Store missing = store(2L, "2222222222");
        when(storeNtsCheckRepository.findAllById(anyList())).thenReturn(List.of());
        when(storeRepository.getReferenceById(1L)).thenReturn(confirmed);
        when(storeRepository.getReferenceById(2L)).thenReturn(missing);

        storeNtsCheckService.markChecked(
                List.of(confirmed, missing),
                Map.of("1234567890", new BusinessStatus("1234567890", BusinessState.ACTIVE, null)),
                ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        List<StoreNtsCheck> saved = checksCaptor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getCheckResult()).isEqualTo(NtsLookupResult.CONFIRMED);
        assertThat(saved.get(1).getCheckResult()).isEqualTo(NtsLookupResult.UNCONFIRMED);
        assertThat(saved.get(1).getNtsState()).isNull();
        assertThat(saved.get(1).getLastSuccessAt()).isNull();
    }

    @Test
    @DisplayName("markUnconfirmed 는 국세청 상태를 건드리지 않고 조회 실패만 기록한다")
    void markUnconfirmedPreservesState() {
        Store store = store(1L, "1234567890");
        when(storeNtsCheckRepository.findAllById(anyList()))
                .thenReturn(List.of(previouslyConfirmed(store, BusinessState.SUSPENDED)));

        storeNtsCheckService.markUnconfirmed(List.of(store), ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getCheckResult()).isEqualTo(NtsLookupResult.UNCONFIRMED);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.SUSPENDED);
        assertThat(saved.getLastSuccessAt()).isEqualTo(EARLIER);
        assertThat(saved.getLastAttemptAt()).isEqualTo(ATTEMPTED_AT);

        verify(storeNtsChangeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("markNoBizNo 는 조회 결과만 NO_BIZ_NO 로 바꾸고 이전 국세청 상태는 보존한다")
    void markNoBizNoKeepsPreviousState() {
        Store store = store(1L, null);
        when(storeNtsCheckRepository.findAllById(anyList()))
                .thenReturn(List.of(previouslyConfirmed(store, BusinessState.ACTIVE)));

        storeNtsCheckService.markNoBizNo(List.of(store), ATTEMPTED_AT);

        verify(storeNtsCheckRepository).saveAll(checksCaptor.capture());
        StoreNtsCheck saved = checksCaptor.getValue().get(0);
        assertThat(saved.getCheckResult()).isEqualTo(NtsLookupResult.NO_BIZ_NO);
        assertThat(saved.getNtsState()).isEqualTo(BusinessState.ACTIVE);
        assertThat(saved.getBizNo()).isEqualTo("1234567890");
        assertThat(saved.getLastSuccessAt()).isEqualTo(EARLIER);
        assertThat(saved.getLastAttemptAt()).isEqualTo(ATTEMPTED_AT);

        verify(storeNtsChangeRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("대상이 없으면 DB 를 건드리지 않는다")
    void doesNothingWhenNoStores() {
        storeNtsCheckService.markChecked(List.of(), Map.of(), ATTEMPTED_AT);
        storeNtsCheckService.markUnconfirmed(List.of(), ATTEMPTED_AT);
        storeNtsCheckService.markNoBizNo(List.of(), ATTEMPTED_AT);

        verifyNoInteractions(storeNtsCheckRepository, storeNtsChangeRepository, storeRepository);
    }
}
