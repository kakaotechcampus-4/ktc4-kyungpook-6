package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.ExcludedStore;
import com.ktc4.backend.domain.store.dto.InvestigationTargets;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.service.NtsProposedChanges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DB 조회를 가짜(Mockito)로 바꿔 나누는 규칙만 검증한다. 가게 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("InvestigationTargetSelector")
class InvestigationTargetSelectorTest {

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 22, 3, 0);

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private InvestigationTargetSelector selector;

    private static Store store(long storeId, StoreStatus status, String bizNo) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(status)
                .bizNo(bizNo)
                .build();
        // storeId 는 DB 가 채우는 값이라 builder 에 없어서 직접 넣는다
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    private static StoreWithNtsCheck checked(long storeId, StoreStatus status,
                                             NtsLookupResult result, BusinessState state) {
        Store store = store(storeId, status, result == NtsLookupResult.NO_BIZ_NO ? null : "0000000000");
        return new StoreWithNtsCheck(store, StoreNtsCheck.builder()
                .store(store)
                .bizNo(store.getBizNo())
                .checkResult(result)
                .ntsState(state)
                .lastAttemptAt(CHECKED_AT)
                .lastSuccessAt(state == null ? null : CHECKED_AT)
                .build());
    }

    private static StoreWithNtsCheck notCheckedYet(long storeId) {
        return new StoreWithNtsCheck(store(storeId, StoreStatus.OPEN, "0000000000"), null);
    }

    private InvestigationTargets select(List<Long> storeIds, StoreWithNtsCheck... rows) {
        when(storeRepository.findWithNtsCheckByStoreIdIn(any())).thenReturn(List.of(rows));
        return selector.select(storeIds);
    }

    private static List<Long> storeIds(List<StoreCheckResponse> checks) {
        return checks.stream().map(StoreCheckResponse::storeId).toList();
    }

    @ParameterizedTest(name = "[{index}] 우리 {0} / 국세청 {1}")
    @CsvSource({
            "OPEN,      ACTIVE",
            "SUSPENDED, SUSPENDED"
    })
    @DisplayName("국세청과 상태가 같으면 AI 조사 대상이다")
    void matchGoesToAi(StoreStatus internal, BusinessState nts) {
        InvestigationTargets targets = select(List.of(1L), checked(1L, internal, NtsLookupResult.CONFIRMED, nts));

        assertThat(storeIds(targets.aiTargets())).containsExactly(1L);
        assertThat(targets.resolvedByNts()).isEmpty();
        assertThat(targets.excluded()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] 우리 {0} / 국세청 {1}")
    @CsvSource({
            "OPEN,      SUSPENDED",
            "OPEN,      CLOSED",
            "SUSPENDED, ACTIVE",
            "SUSPENDED, CLOSED",
            "CLOSED,    ACTIVE",
            "CLOSED,    SUSPENDED"
    })
    @DisplayName("국세청과 상태가 다르면 AI 로 넘기지 않고 1차 수정안으로 끝낸다")
    void mismatchIsResolvedByNts(StoreStatus internal, BusinessState nts) {
        InvestigationTargets targets = select(List.of(1L), checked(1L, internal, NtsLookupResult.CONFIRMED, nts));

        assertThat(storeIds(targets.resolvedByNts())).containsExactly(1L);
        assertThat(targets.aiTargets()).isEmpty();
        assertThat(targets.excluded()).isEmpty();
    }

    @Test
    @DisplayName("우리도 국세청도 폐업이면 조사하지 않는다")
    void closedOnBothSidesIsExcluded() {
        InvestigationTargets targets = select(List.of(1L),
                checked(1L, StoreStatus.CLOSED, NtsLookupResult.CONFIRMED, BusinessState.CLOSED));

        assertThat(targets.excluded())
                .containsExactly(new ExcludedStore(1L, InvestigationExclusionReason.ALREADY_CLOSED));
        assertThat(targets.aiTargets()).isEmpty();
        assertThat(targets.resolvedByNts()).isEmpty();
    }

    @Test
    @DisplayName("사업자번호가 없거나 국세청에 없는 번호면 데이터 문제로 제외한다")
    void dataProblemIsExcluded() {
        InvestigationTargets targets = select(List.of(1L, 2L),
                checked(1L, StoreStatus.OPEN, NtsLookupResult.NO_BIZ_NO, null),
                checked(2L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.NOT_REGISTERED));

        assertThat(targets.excluded()).containsExactly(
                new ExcludedStore(1L, InvestigationExclusionReason.DATA_PROBLEM),
                new ExcludedStore(2L, InvestigationExclusionReason.DATA_PROBLEM));
    }

    @Test
    @DisplayName("번호가 지워진 가게는 옛 국세청 상태가 남아 있어도 데이터 문제로 제외한다")
    void noBizNoWithStaleStateIsDataProblem() {
        // 남아 있는 상태는 옛 번호 기준이라 다른 사업자의 것일 수 있다 — 수정안도 AI 조사도 내면 안 된다
        InvestigationTargets targets = select(List.of(1L),
                checked(1L, StoreStatus.OPEN, NtsLookupResult.NO_BIZ_NO, BusinessState.CLOSED));

        assertThat(targets.excluded())
                .containsExactly(new ExcludedStore(1L, InvestigationExclusionReason.DATA_PROBLEM));
    }

    @Test
    @DisplayName("우리 상태가 UNKNOWN 이어도 국세청에 없는 번호면 데이터 문제가 먼저다")
    void dataProblemComesBeforeNotComparable() {
        InvestigationTargets targets = select(List.of(1L),
                checked(1L, StoreStatus.UNKNOWN, NtsLookupResult.CONFIRMED, BusinessState.NOT_REGISTERED));

        assertThat(targets.excluded())
                .containsExactly(new ExcludedStore(1L, InvestigationExclusionReason.DATA_PROBLEM));
    }

    @Test
    @DisplayName("국세청과 비교할 수 없으면 제외한다 — 대조 기록이 없거나, 확인에 실패했거나, 우리 상태가 UNKNOWN")
    void notComparableIsExcluded() {
        InvestigationTargets targets = select(List.of(1L, 2L, 3L),
                notCheckedYet(1L),
                checked(2L, StoreStatus.OPEN, NtsLookupResult.UNCONFIRMED, null),
                checked(3L, StoreStatus.UNKNOWN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE));

        assertThat(targets.excluded()).containsExactly(
                new ExcludedStore(1L, InvestigationExclusionReason.NO_NTS_CHECK),
                new ExcludedStore(2L, InvestigationExclusionReason.NO_NTS_CHECK),
                new ExcludedStore(3L, InvestigationExclusionReason.NO_NTS_CHECK));
    }

    @Test
    @DisplayName("없는 가게 번호는 그 가게만 제외하고 나머지는 그대로 나눈다")
    void unknownStoreIdIsExcludedAlone() {
        InvestigationTargets targets = select(List.of(1L, 999L),
                checked(1L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE));

        assertThat(storeIds(targets.aiTargets())).containsExactly(1L);
        assertThat(targets.excluded())
                .containsExactly(new ExcludedStore(999L, InvestigationExclusionReason.STORE_NOT_FOUND));
    }

    @Test
    @DisplayName("같은 가게 번호가 여러 번 와도 한 번만 다룬다")
    void duplicatedStoreIdIsHandledOnce() {
        InvestigationTargets targets = select(List.of(1L, 1L, 999L, 999L),
                checked(1L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE));

        assertThat(storeIds(targets.aiTargets())).containsExactly(1L);
        assertThat(targets.excluded()).hasSize(1);
    }

    @Test
    @DisplayName("담당자가 고른 순서를 지킨다")
    void keepsRequestedOrder() {
        // 조회 결과는 storeId 오름차순으로 오지만, 돌려줄 때는 고른 순서다
        InvestigationTargets targets = select(List.of(3L, 1L, 2L),
                checked(1L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE),
                checked(2L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE),
                checked(3L, StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE));

        assertThat(storeIds(targets.aiTargets())).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("고른 가게가 없으면 DB 를 조회하지 않는다")
    void emptySelectionSkipsQuery() {
        InvestigationTargets targets = selector.select(List.of());

        assertThat(targets.aiTargets()).isEmpty();
        assertThat(targets.resolvedByNts()).isEmpty();
        assertThat(targets.excluded()).isEmpty();
        verify(storeRepository, never()).findWithNtsCheckByStoreIdIn(any());
    }

    @Test
    @DisplayName("있을 수 있는 모든 조합에서 가게는 세 묶음 중 정확히 한 곳에 들어가고, 1차 수정안은 resolvedByNts 에만 있다")
    void everyStoreLandsInExactlyOneGroup() {
        // 조회 실패(UNCONFIRMED)·번호 없음(NO_BIZ_NO)이어도 예전에 확인한 국세청 상태는 남아 있을 수 있다. 그 조합까지 넣는다.
        BusinessState[] states = {null, BusinessState.ACTIVE, BusinessState.SUSPENDED, BusinessState.CLOSED,
                BusinessState.NOT_REGISTERED};
        List<StoreWithNtsCheck> rows = new ArrayList<>();
        long storeId = 0;
        for (NtsLookupResult result : NtsLookupResult.values()) {
            for (StoreStatus status : StoreStatus.values()) {
                for (BusinessState state : states) {
                    if (result == NtsLookupResult.CONFIRMED && state == null) {
                        continue;   // 확인에 성공했는데 상태가 없는 기록은 만들어지지 않는다
                    }
                    rows.add(checked(++storeId, status, result, state));
                }
            }
        }
        rows.add(notCheckedYet(++storeId));
        List<Long> ids = rows.stream().map(row -> row.store().getStoreId()).toList();

        InvestigationTargets targets = select(ids, rows.toArray(StoreWithNtsCheck[]::new));

        List<Long> landed = new ArrayList<>(storeIds(targets.aiTargets()));
        landed.addAll(storeIds(targets.resolvedByNts()));
        landed.addAll(targets.excluded().stream().map(ExcludedStore::storeId).toList());
        assertThat(landed).containsExactlyInAnyOrderElementsOf(ids);

        // AI 를 안 부르는 가게에 수정안이 없으면 담당자 화면에 아무것도 안 뜬다 — 두 규칙이 어긋나지 않는지 본다
        assertThat(targets.resolvedByNts())
                .allSatisfy(check -> assertThat(NtsProposedChanges.from(check.statusComparison())).isNotEmpty());
        assertThat(targets.aiTargets())
                .allSatisfy(check -> assertThat(NtsProposedChanges.from(check.statusComparison())).isEmpty());
        assertThat(targets.aiTargets())
                .allSatisfy(check -> assertThat(check.internalStatus()).isNotEqualTo(StoreStatus.CLOSED));
    }
}
