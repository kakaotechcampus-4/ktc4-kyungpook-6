package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.ExcludedStore;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreLastChange;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreSnapshot;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreChangeHistoryRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

// DB 조회를 가짜(Mockito)로 바꿔 조립·필터 선택 로직만 검증한다. 가게 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreService")
class StoreServiceTest {

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 22, 3, 0);

    private static final StoreChange ADMIN_EDIT = StoreChange.storeEdit(1L);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 9, 2, 14, 30);

    @Mock
    private StoreRepository storeRepository;

    // 변경 기록은 실제 DB 로 따로 본다(StoreServiceUpdateTest) — 여기서는 조립 로직만
    @Mock
    private StoreChangeHistoryService storeChangeHistoryService;

    @Mock
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Mock
    private StoreChangeHistoryRepository storeChangeHistoryRepository;

    // 번호가 바뀌면 커밋 뒤 재조회를 알린다. 실제로 내보내는지는 StoreServiceUpdateTest 가 본다
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private StoreService storeService;

    /*
      수정 경로가 앞뒤 스냅숏을 견줘 사업자등록번호가 바뀌었는지 본다. mock 이 null 을 주면
      그 비교에서 터지므로 진짜 스냅숏을 만들어 준다. 영업시간·메뉴는 이 테스트의 관심이 아니라 비워 둔다.
    */
    @BeforeEach
    void stubSnapshots() {
        lenient().when(storeChangeHistoryService.snapshot(any()))
                .thenAnswer(invocation -> StoreSnapshot.of(invocation.getArgument(0), List.of(), List.of()));
    }

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

    /*
      화면이 "마지막 확인"(사람이 직접 본 날)과 "최근 변경"(값이 바뀐 날)을 따로 보여주기 위한 값이다.
      수정안을 반영해도 확인일은 오르지 않아서, 날짜가 하나뿐이면 "반영했는데 왜 그대로지" 로 보인다.
    */

    @Nested
    @DisplayName("getStores — 최근 변경 시각")
    class LastChangedAt {

        /*
          목록 조회는 국세청 확인 기록을 바깥 조인으로 함께 읽는다(제외 판정에 필요). 이 묶음은 변경 시각만
          보므로 기록은 비워 둔다 — 기록이 없으면 NO_NTS_CHECK 로 빠지는데, 그 판정은 아래 ExclusionPreview 가 본다.
        */
        private Page<StoreWithNtsCheck> storePage(Store... stores) {
            List<StoreWithNtsCheck> rows = List.of(stores).stream()
                    .map(store -> new StoreWithNtsCheck(store, null))
                    .toList();
            return new PageImpl<>(rows, PageRequest.of(0, 20), rows.size());
        }

        @Test
        @DisplayName("바뀐 적이 있으면 그 시각을 함께 내려준다")
        void fillsLastChangedAt() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findAllWithNtsCheck(any(Pageable.class))).thenReturn(storePage(store));
            when(storeChangeHistoryRepository.findLastChangedAtByStoreIds(List.of(1L)))
                    .thenReturn(List.of(new StoreLastChange(1L, CHANGED_AT)));

            PageResponse<StoreResponse> response = storeService.getStores(0, 20);

            assertThat(response.content()).singleElement()
                    .extracting(StoreResponse::lastChangedAt).isEqualTo(CHANGED_AT);
        }

        @Test
        @DisplayName("바뀐 적이 없는 가게는 비워 둔다 — 날짜를 지어내지 않는다")
        void leavesLastChangedAtEmpty() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findAllWithNtsCheck(any(Pageable.class))).thenReturn(storePage(store));
            when(storeChangeHistoryRepository.findLastChangedAtByStoreIds(List.of(1L)))
                    .thenReturn(List.of());

            PageResponse<StoreResponse> response = storeService.getStores(0, 20);

            assertThat(response.content()).singleElement()
                    .extracting(StoreResponse::lastChangedAt).isNull();
        }

        @Test
        @DisplayName("한 쪽을 한 번에 묻는다 — 가게마다 따로 물으면 N+1 이다")
        void asksOnceForThePage() {
            when(storeRepository.findAllWithNtsCheck(any(Pageable.class))).thenReturn(storePage(
                    store(1L, StoreStatus.OPEN, "1234567890"),
                    store(2L, StoreStatus.OPEN, "2234567890"),
                    store(3L, StoreStatus.OPEN, "3234567890")));
            when(storeChangeHistoryRepository.findLastChangedAtByStoreIds(List.of(1L, 2L, 3L)))
                    .thenReturn(List.of(new StoreLastChange(2L, CHANGED_AT)));

            storeService.getStores(0, 20);

            verify(storeChangeHistoryRepository, times(1)).findLastChangedAtByStoreIds(anyCollection());
        }

        @Test
        @DisplayName("가게가 없으면 아예 묻지 않는다")
        void asksNothingWhenPageIsEmpty() {
            when(storeRepository.findAllWithNtsCheck(any(Pageable.class))).thenReturn(storePage());

            storeService.getStores(0, 20);

            verify(storeChangeHistoryRepository, never()).findLastChangedAtByStoreIds(anyCollection());
        }
    }

    /*
      담당자가 60곳을 골라 조사를 시작했는데 56곳만 조사된 일이 있었다. 차이 4곳은 국세청 대조 기록이 없어
      빠진 가게였는데, 화면이 미리도 나중에도 그걸 설명하지 않았다. 목록이 가게마다 "빠지는지·왜" 를 들고
      있어야 화면이 미리 알려 줄 수 있다.
    */
    @Nested
    @DisplayName("getStores — 조사 제외 예고")
    class ExclusionPreview {

        private StoreWithNtsCheck checked(long storeId, StoreStatus status,
                                          NtsLookupResult result, BusinessState state) {
            Store store = store(storeId, status, result == NtsLookupResult.NO_BIZ_NO ? null : "1234567890");
            return new StoreWithNtsCheck(store, check(store, result, state, null));
        }

        private List<StoreResponse> getStores(StoreWithNtsCheck... rows) {
            when(storeRepository.findAllWithNtsCheck(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(rows), PageRequest.of(0, 20), rows.length));
            return storeService.getStores(0, 20).content();
        }

        @Test
        @DisplayName("국세청 대조 기록이 아직 없는 가게는 NO_NTS_CHECK 로 빠진다고 알려준다")
        void marksNotYetCheckedStore() {
            StoreResponse response = getStores(
                    new StoreWithNtsCheck(store(1L, StoreStatus.OPEN, "1234567890"), null)).get(0);

            assertThat(response.investigationExcluded()).isTrue();
            assertThat(response.investigationExclusionReason())
                    .isEqualTo(InvestigationExclusionReason.NO_NTS_CHECK);
        }

        @Test
        @DisplayName("조사 대상인 가게는 빠지지 않는다고 알려준다 — 사유를 지어내지 않는다")
        void leavesTargetStoreUnmarked() {
            StoreResponse response = getStores(
                    checked(1L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE)).get(0);

            assertThat(response.investigationExcluded()).isFalse();
            assertThat(response.investigationExclusionReason()).isNull();
        }

        @Test
        @DisplayName("국세청과 상태가 다른 가게도 빠지지 않는다 — AI 없이 1차 수정안으로 조사된다")
        void mismatchIsStillInvestigated() {
            StoreResponse response = getStores(
                    checked(1L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.CLOSED)).get(0);

            assertThat(response.investigationExcluded()).isFalse();
        }

        /*
          제외 판정이 조사 생성과 같은 코드인지를 고정한다. 규칙을 베껴 두면 목록이 미리 보여 준 제외와
          실제로 조사에서 빠지는 가게가 갈라지고, 그러면 담당자는 숫자가 줄어든 이유를 끝까지 알 수 없다.
        */
        @Test
        @DisplayName("사유가 조사 생성(InvestigationTargetSelector)이 내는 것과 가게별로 같다")
        void matchesInvestigationTargetSelector() {
            List<StoreWithNtsCheck> rows = List.of(
                    new StoreWithNtsCheck(store(1L, StoreStatus.OPEN, "1234567890"), null),
                    checked(2L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE),
                    checked(3L, StoreStatus.CLOSED, NtsLookupResult.CONFIRMED, BusinessState.CLOSED),
                    checked(4L, StoreStatus.SUSPENDED, NtsLookupResult.CONFIRMED, BusinessState.SUSPENDED),
                    checked(5L, StoreStatus.OPEN, NtsLookupResult.NO_BIZ_NO, null),
                    checked(6L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.CLOSED));

            when(storeRepository.findAllWithNtsCheck(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), rows.size()));
            when(storeRepository.findWithNtsCheckByStoreIdIn(any())).thenReturn(rows);

            Map<Long, InvestigationExclusionReason> fromList = storeService.getStores(0, 20).content().stream()
                    .filter(StoreResponse::investigationExcluded)
                    .collect(Collectors.toMap(StoreResponse::storeId,
                            StoreResponse::investigationExclusionReason));

            List<Long> storeIds = rows.stream().map(row -> row.store().getStoreId()).toList();
            Map<Long, InvestigationExclusionReason> fromSelector =
                    new InvestigationTargetSelector(storeRepository).select(storeIds).excluded().stream()
                            .collect(Collectors.toMap(ExcludedStore::storeId, ExcludedStore::reason));

            assertThat(fromList).isEqualTo(fromSelector);
            // 비교가 "둘 다 비어서 같다" 로 통과하지 않게, 실제로 사유가 나오는 묶음인지 함께 고정한다
            assertThat(fromList).containsOnlyKeys(1L, 3L, 4L, 5L)
                    .containsEntry(1L, InvestigationExclusionReason.NO_NTS_CHECK)
                    .containsEntry(3L, InvestigationExclusionReason.ALREADY_CLOSED)
                    .containsEntry(4L, InvestigationExclusionReason.ALREADY_SUSPENDED)
                    .containsEntry(5L, InvestigationExclusionReason.DATA_PROBLEM);
        }
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
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = StoreUpdateRequest.ofBasic(
                    "새이름(주)", "대구광역시 북구 대학로 80-1", "010-9999-0000", StoreStatus.CLOSED);

            StoreResponse response = storeService.updateStore(1L, request, ADMIN_EDIT);

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
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = StoreUpdateRequest.ofBasic(null, null, "010-9999-0000", null);

            storeService.updateStore(1L, request, ADMIN_EDIT);

            assertThat(store.getName()).isEqualTo("예시분식");
            assertThat(store.getNameNormalized()).isEqualTo("예시분식");
        }

        @Test
        @DisplayName("phone 을 빈 문자열로 보내면 실제로 지워진다")
        void clearsPhoneWithEmptyString() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            StoreUpdateRequest request = StoreUpdateRequest.ofBasic(null, null, "", null);

            storeService.updateStore(1L, request, ADMIN_EDIT);

            assertThat(store.getPhone()).isEmpty();
        }

        @Test
        @DisplayName("storeId 에 해당하는 가게가 없으면 STORE_NOT_FOUND 를 던진다")
        void throwsWhenStoreNotFound() {
            when(storeRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());
            StoreUpdateRequest request = StoreUpdateRequest.ofBasic("새이름", null, null, null);

            assertThatThrownBy(() -> storeService.updateStore(999L, request, ADMIN_EDIT))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }

        /*
          여기부터 사업자등록번호 — 주입은 9칸을 받는데 수정은 네 칸뿐이라 오타를 고칠 길이 없었다(PROMPT-161).
        */

        private StoreUpdateRequest bizNoOnly(String bizNo) {
            return new StoreUpdateRequest(null, null, null, null, null, null, null, null, bizNo);
        }

        private StoreNtsCheck confirmedClosed(Store store, String bizNo) {
            return StoreNtsCheck.builder()
                    .store(store).bizNo(bizNo)
                    .checkResult(NtsLookupResult.CONFIRMED).ntsState(BusinessState.CLOSED)
                    .lastAttemptAt(CHECKED_AT).lastSuccessAt(CHECKED_AT)
                    .build();
        }

        @Test
        @DisplayName("하이픈이 섞여 와도 숫자만 남겨 저장한다")
        void normalizesBizNo() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));

            storeService.updateStore(1L, bizNoOnly("222-22-22222"), ADMIN_EDIT);

            assertThat(store.getBizNo()).isEqualTo("2222222222");
        }

        @Test
        @DisplayName("숫자 10자리가 아니면 INVALID_BIZ_NO — 가게 값은 하나도 바뀌지 않는다")
        void rejectsBizNoThatIsNotTenDigits() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));

            assertThatThrownBy(() -> storeService.updateStore(1L, bizNoOnly("123-45"), ADMIN_EDIT))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_BIZ_NO);
            assertThat(store.getBizNo()).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("번호를 바꾸면 국세청 확인 기록을 되돌린다 — 저장된 답은 옛 번호로 받은 것이다")
        void resetsNtsCheckWhenBizNoChanged() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            StoreNtsCheck check = confirmedClosed(store, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            when(storeNtsCheckRepository.findById(1L)).thenReturn(Optional.of(check));

            storeService.updateStore(1L, bizNoOnly("2222222222"), ADMIN_EDIT);

            assertThat(check.getNtsState()).isNull();
            assertThat(check.getCheckResult()).isEqualTo(NtsLookupResult.UNCONFIRMED);
            // 마지막 조회에 쓴 번호는 남긴다 — 배치가 이 값으로 "다른 사업자" 를 알아본다
            assertThat(check.getBizNo()).isEqualTo("1234567890");
        }

        /*
          전에는 "findById 를 한 번도 부르지 않는다" 로 봤는데, 응답에 담을 조사 제외 사유를 구하려고
          읽기는 한다. 지켜야 하는 건 읽지 않는 것이 아니라 되돌리지 않는 것이라, 호출 여부가 아니라
          기록의 값이 그대로인지를 본다 — 더 좁은 약속이 아니라 더 정확한 약속이다.
        */
        @Test
        @DisplayName("같은 번호를 다시 보내면 국세청 확인 기록을 되돌리지 않는다")
        void keepsNtsCheckWhenBizNoUnchanged() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            StoreNtsCheck check = confirmedClosed(store, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            when(storeNtsCheckRepository.findById(1L)).thenReturn(Optional.of(check));

            storeService.updateStore(1L, bizNoOnly("123-45-67890"), ADMIN_EDIT);

            assertThat(check.getCheckResult()).isEqualTo(NtsLookupResult.CONFIRMED);
            assertThat(check.getNtsState()).isEqualTo(BusinessState.CLOSED);
            assertThat(check.getLastSuccessAt()).isEqualTo(CHECKED_AT);
        }

        @Test
        @DisplayName("한 번도 조회하지 않은 가게는 되돌릴 기록이 없어도 그냥 지나간다")
        void toleratesMissingNtsCheck() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(store));
            when(storeNtsCheckRepository.findById(1L)).thenReturn(Optional.empty());

            storeService.updateStore(1L, bizNoOnly("2222222222"), ADMIN_EDIT);

            assertThat(store.getBizNo()).isEqualTo("2222222222");
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
        @DisplayName("확인 시각을 받으면 그 시각으로 갱신한다 — 확인 기록과 가게 확인일을 같은 시각으로 맞출 때 쓴다")
        void setsLastCheckedAtToGivenTime() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));
            LocalDateTime checkedAt = LocalDateTime.of(2026, 10, 7, 12, 0);

            StoreResponse response = storeService.confirmStore(1L, checkedAt);

            assertThat(store.getLastCheckedAt()).isEqualTo(checkedAt);
            assertThat(response.lastCheckedAt()).isEqualTo(checkedAt);
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

    @Nested
    @DisplayName("findStore")
    class FindStore {

        @Test
        @DisplayName("storeId 에 해당하는 가게 엔티티를 돌려준다")
        void returnsStore() {
            Store store = store(1L, StoreStatus.OPEN, "1234567890");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(store));

            assertThat(storeService.findStore(1L)).isSameAs(store);
        }

        @Test
        @DisplayName("storeId 에 해당하는 가게가 없으면 STORE_NOT_FOUND 를 던진다")
        void throwsWhenStoreNotFound() {
            when(storeRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeService.findStore(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }
    }
}
