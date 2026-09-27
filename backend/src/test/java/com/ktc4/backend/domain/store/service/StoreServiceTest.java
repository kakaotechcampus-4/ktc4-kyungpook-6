package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DB 조회를 가짜(Mockito)로 바꿔 조립·필터 선택 로직만 검증한다. 가게 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreService")
class StoreServiceTest {

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 22, 3, 0);

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreService storeService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private static Store store(long storeId, StoreStatus status, String bizNo) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .lat(12.3456)
                .lng(123.4567)
                .phone("000-1234-5678")
                .status(status)
                .bizNo(bizNo)
                .build();
        // storeId 는 DB 가 채우는 값이라 builder 에 없어서 직접 넣는다
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    private static StoreNtsCheck check(Store store, NtsLookupResult result, BusinessState state, LocalDate closedAt) {
        return StoreNtsCheck.builder()
                .store(store)
                .bizNo(store.getBizNo())
                .checkResult(result)
                .ntsState(state)
                .ntsClosedAt(closedAt)
                .lastAttemptAt(CHECKED_AT)
                .lastSuccessAt(state == null ? null : CHECKED_AT)
                .build();
    }

    private static Page<StoreWithNtsCheck> page(StoreWithNtsCheck... rows) {
        return new PageImpl<>(List.of(rows), PageRequest.of(0, 20), rows.length);
    }

    @Test
    @DisplayName("가게 정보와 국세청 확인 결과를 한 줄로 합쳐 내려준다")
    void combinesStoreAndNtsCheck() {
        Store store = store(1L, StoreStatus.OPEN, "1234567890");
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page(new StoreWithNtsCheck(
                store, check(store, NtsLookupResult.CONFIRMED, BusinessState.CLOSED, LocalDate.of(2026, 3, 1)))));

        StoreCheckResponse response = storeService.getNtsChecks(null, 0, 20).content().get(0);

        assertThat(response.storeId()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("예시분식");
        assertThat(response.nameNormalized()).isEqualTo("예시분식");
        assertThat(response.addressRoad()).isEqualTo("가상특별시 예시구 샘플로 123");
        assertThat(response.addressNormalized()).isEqualTo("가상특별시예시구샘플로123");
        assertThat(response.lat()).isEqualTo(12.3456);
        assertThat(response.lng()).isEqualTo(123.4567);
        assertThat(response.phone()).isEqualTo("000-1234-5678");
        assertThat(response.bizNo()).isEqualTo("1234567890");
        assertThat(response.internalStatus()).isEqualTo(StoreStatus.OPEN);
        assertThat(response.ntsLookup()).isEqualTo(NtsLookupResult.CONFIRMED);
        assertThat(response.ntsStatus()).isEqualTo(BusinessState.CLOSED);
        assertThat(response.ntsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(response.statusComparison()).isEqualTo(StatusComparison.OPEN_BUT_CLOSED);
        assertThat(response.statusMismatch()).isTrue();
        assertThat(response.dataProblem()).isFalse();
        assertThat(response.ntsCheckedAt()).isEqualTo(CHECKED_AT);
    }

    @Test
    @DisplayName("아직 확인되지 않은 가게는 UNCONFIRMED 로 내려준다")
    void treatsMissingCheckAsUnconfirmed() {
        Store store = store(1L, StoreStatus.OPEN, "1234567890");
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page(new StoreWithNtsCheck(store, null)));

        StoreCheckResponse response = storeService.getNtsChecks(null, 0, 20).content().get(0);

        assertThat(response.ntsLookup()).isEqualTo(NtsLookupResult.UNCONFIRMED);
        assertThat(response.ntsStatus()).isNull();
        assertThat(response.ntsClosedAt()).isNull();
        assertThat(response.ntsCheckedAt()).isNull();
        assertThat(response.statusComparison()).isEqualTo(StatusComparison.NOT_COMPARABLE);
        assertThat(response.statusMismatch()).isFalse();
        assertThat(response.dataProblem()).isFalse();
    }

    @Test
    @DisplayName("번호가 없어 조회하지 못한 가게는 데이터 문제로 표시한다")
    void marksNoBizNoAsDataProblem() {
        Store store = store(1L, StoreStatus.OPEN, null);
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page(new StoreWithNtsCheck(
                store, check(store, NtsLookupResult.NO_BIZ_NO, null, null))));

        StoreCheckResponse response = storeService.getNtsChecks(null, 0, 20).content().get(0);

        assertThat(response.ntsLookup()).isEqualTo(NtsLookupResult.NO_BIZ_NO);
        assertThat(response.dataProblem()).isTrue();
        assertThat(response.statusMismatch()).isFalse();
    }

    @Test
    @DisplayName("번호가 지워진 가게는 옛 국세청 상태가 남아 있어도 조사 대상이 아니라 데이터 문제다")
    void treatsNoBizNoWithOldStateAsDataProblemOnly() {
        Store store = store(1L, StoreStatus.OPEN, null);
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page(new StoreWithNtsCheck(
                store, check(store, NtsLookupResult.NO_BIZ_NO, BusinessState.CLOSED, LocalDate.of(2026, 3, 1)))));

        StoreCheckResponse response = storeService.getNtsChecks(null, 0, 20).content().get(0);

        assertThat(response.statusComparison()).isEqualTo(StatusComparison.NOT_COMPARABLE);
        assertThat(response.statusMismatch()).isFalse();
        assertThat(response.dataProblem()).isTrue();
        // 옛 상태는 참고용으로 그대로 내려간다
        assertThat(response.ntsStatus()).isEqualTo(BusinessState.CLOSED);
        assertThat(response.ntsClosedAt()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("필터가 없으면 전체를 조회한다")
    void usesFindAllWhenNoFilter() {
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page());

        storeService.getNtsChecks(null, 0, 20);

        verify(storeRepository).findAllWithNtsCheck(any());
        verify(storeRepository, never()).findStatusMismatch(any());
        verify(storeRepository, never()).findDataProblem(any());
    }

    @Test
    @DisplayName("STATUS_MISMATCH 필터는 상태 불일치 조회를 쓴다")
    void usesStatusMismatchQuery() {
        when(storeRepository.findStatusMismatch(any())).thenReturn(page());

        storeService.getNtsChecks(NtsCheckFilter.STATUS_MISMATCH, 0, 20);

        verify(storeRepository).findStatusMismatch(any());
        verify(storeRepository, never()).findAllWithNtsCheck(any());
    }

    @Test
    @DisplayName("DATA_PROBLEM 필터는 데이터 문제 조회를 쓴다")
    void usesDataProblemQuery() {
        when(storeRepository.findDataProblem(any())).thenReturn(page());

        storeService.getNtsChecks(NtsCheckFilter.DATA_PROBLEM, 0, 20);

        verify(storeRepository).findDataProblem(any());
        verify(storeRepository, never()).findAllWithNtsCheck(any());
    }

    @Test
    @DisplayName("요청한 페이지 번호와 건수를 그대로 조회에 넘긴다")
    void passesPageRequest() {
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page());

        storeService.getNtsChecks(null, 2, 50);

        verify(storeRepository).findAllWithNtsCheck(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("페이지 정보를 그대로 담아 돌려준다")
    void returnsPageInfo() {
        Store store = store(1L, StoreStatus.OPEN, "1234567890");
        when(storeRepository.findAllWithNtsCheck(any())).thenReturn(page(new StoreWithNtsCheck(store, null)));

        PageResponse<StoreCheckResponse> response = storeService.getNtsChecks(null, 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.limit()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Nested
    @DisplayName("updateStore")
    class UpdateStore {

        @Test
        @DisplayName("담아 보낸 필드를 재정규화해 반영하고, 반영된 값을 그대로 응답한다")
        void updatesFieldsAndRenormalizes() {
            // name/addressRoad 를 각각 그 필드 전용 정규화 규칙으로만 통과시켜야 알아챌 수 있는 값으로 고른다
            // (법인 표기 제거는 normalizeName 만의 규칙, 하이픈 보존은 normalizeAddress 만의 규칙) —
            // 두 정규화 호출이 서로 뒤바뀌어도 통과해버리는 값(단순 공백 제거만으로 갈리는 값)은 쓰지 않는다.
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = new StoreUpdateRequest(
                    "새이름(주)", "대구광역시 북구 대학로 80-1", "010-9999-0000", StoreStatus.CLOSED);

            StoreResponse response = storeService.updateStore(1L, request);

            assertThat(store.getName()).isEqualTo("새이름(주)");
            assertThat(store.getNameNormalized()).isEqualTo("새이름");
            assertThat(store.getAddressRoad()).isEqualTo("대구광역시 북구 대학로 80-1");
            assertThat(store.getAddressNormalized()).isEqualTo("대구광역시북구대학로80-1");
            assertThat(store.getPhone()).isEqualTo("010-9999-0000");
            assertThat(store.getStatus()).isEqualTo(StoreStatus.CLOSED);
            assertThat(response.name()).isEqualTo("새이름(주)");
            assertThat(response.status()).isEqualTo(StoreStatus.CLOSED);
        }

        @Test
        @DisplayName("name 을 안 보내면 nameNormalized 도 그대로다 — 재정규화하지 않는다")
        void keepsNameNormalizedWhenNameNotSent() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = new StoreUpdateRequest(null, null, "010-9999-0000", null);

            storeService.updateStore(1L, request);

            assertThat(store.getName()).isEqualTo("예시분식");
            assertThat(store.getNameNormalized()).isEqualTo("예시분식");
        }

        @Test
        @DisplayName("phone 을 빈 문자열로 보내면 실제로 지워진다")
        void clearsPhoneWithEmptyString() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = new StoreUpdateRequest(null, null, "", null);

            storeService.updateStore(1L, request);

            assertThat(store.getPhone()).isEmpty();
        }

        @Test
        @DisplayName("storeId 에 해당하는 가게가 없으면 STORE_NOT_FOUND 를 던진다")
        void throwsWhenStoreNotFound() {
            when(storeRepository.findById(anyLong())).thenReturn(Optional.empty());
            StoreUpdateRequest request = new StoreUpdateRequest("새이름", null, null, null);

            assertThatThrownBy(() -> storeService.updateStore(999L, request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("confirmStore")
    class ConfirmStore {

        @Test
        @DisplayName("현재 시각으로 lastCheckedAt 을 갱신하고, 갱신된 값을 그대로 응답한다")
        void setsLastCheckedAtToNow() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
            LocalDateTime before = LocalDateTime.now();

            StoreResponse response = storeService.confirmStore(1L);

            LocalDateTime after = LocalDateTime.now();
            assertThat(store.getLastCheckedAt()).isBetween(before, after);
            assertThat(response.lastCheckedAt()).isEqualTo(store.getLastCheckedAt());
        }

        @Test
        @DisplayName("storeId 에 해당하는 가게가 없으면 STORE_NOT_FOUND 를 던진다")
        void throwsWhenStoreNotFound() {
            when(storeRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeService.confirmStore(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }
    }
}
