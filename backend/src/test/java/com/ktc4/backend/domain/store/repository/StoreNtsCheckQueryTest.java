package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 조사 자료 목록 질의 통합 테스트.
 *
 * <p>가게 상태와 국세청 상태를 비교해 거르는 조건은 자바(StoreCheckResponse)에도, 질의(JPQL)에도
 * 있다. 두 곳이 어긋나면 "목록에는 있는데 불일치가 아닌" 행이 생기므로, 마지막 테스트에서
 * 질의 결과와 응답 필드(statusMismatch·dataProblem)가 같은지 확인한다.
 */
class StoreNtsCheckQueryTest extends PostgresContainerTest {

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 9, 22, 3, 0);

    private Store persistStore(String name, StoreStatus status, String bizNo) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name)
                .nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(status)
                .bizNo(bizNo)
                .build());
    }

    private void persistCheck(Store store, NtsLookupResult checkResult, BusinessState ntsState) {
        entityManager.persistAndFlush(StoreNtsCheck.builder()
                .store(store)
                .bizNo(store.getBizNo())
                .checkResult(checkResult)
                .ntsState(ntsState)
                .lastAttemptAt(CHECKED_AT)
                .lastSuccessAt(ntsState == null ? null : CHECKED_AT)
                .build());
    }

    @Test
    void 확인기록이_없는_가게도_목록에_나온다() {
        Store checked = persistStore("예시분식", StoreStatus.OPEN, "1111111111");
        persistCheck(checked, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE);
        Store notCheckedYet = persistStore("샘플카페", StoreStatus.OPEN, "2222222222");
        entityManager.clear();

        Page<StoreWithNtsCheck> page = storeRepository.findAllWithNtsCheck(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(row -> row.store().getStoreId())
                .containsExactly(checked.getStoreId(), notCheckedYet.getStoreId());
        assertThat(page.getContent().get(1).check()).isNull();
    }

    @Test
    void 상태가_다른_가게만_고른다() {
        Store mismatch = persistStore("예시분식", StoreStatus.OPEN, "1111111111");
        persistCheck(mismatch, NtsLookupResult.CONFIRMED, BusinessState.CLOSED);
        Store match = persistStore("샘플카페", StoreStatus.OPEN, "2222222222");
        persistCheck(match, NtsLookupResult.CONFIRMED, BusinessState.ACTIVE);
        entityManager.clear();

        Page<StoreWithNtsCheck> page = storeRepository.findStatusMismatch(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(row -> row.store().getStoreId())
                .containsExactly(mismatch.getStoreId());
    }

    @Test
    void 우리_상태가_UNKNOWN_이면_상태_불일치로_보지_않는다() {
        Store unknown = persistStore("예시분식", StoreStatus.UNKNOWN, "1111111111");
        persistCheck(unknown, NtsLookupResult.CONFIRMED, BusinessState.CLOSED);
        entityManager.clear();

        assertThat(storeRepository.findStatusMismatch(PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void 국세청_미등록은_상태_불일치가_아니라_데이터_문제다() {
        Store notRegistered = persistStore("예시분식", StoreStatus.OPEN, "0000000000");
        persistCheck(notRegistered, NtsLookupResult.CONFIRMED, BusinessState.NOT_REGISTERED);
        entityManager.clear();

        assertThat(storeRepository.findStatusMismatch(PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(storeRepository.findDataProblem(PageRequest.of(0, 10)).getContent())
                .extracting(row -> row.store().getStoreId())
                .containsExactly(notRegistered.getStoreId());
    }

    @Test
    void 번호가_없어_조회하지_못한_가게도_데이터_문제다() {
        Store noBizNo = persistStore("샘플카페", StoreStatus.OPEN, null);
        persistCheck(noBizNo, NtsLookupResult.NO_BIZ_NO, null);
        Store unconfirmed = persistStore("예시국밥", StoreStatus.OPEN, "3333333333");
        persistCheck(unconfirmed, NtsLookupResult.UNCONFIRMED, null);
        entityManager.clear();

        // 조회 실패(UNCONFIRMED)는 다시 조회하면 확인될 수 있어 데이터 문제가 아니다
        assertThat(storeRepository.findDataProblem(PageRequest.of(0, 10)).getContent())
                .extracting(row -> row.store().getStoreId())
                .containsExactly(noBizNo.getStoreId());
    }

    @Test
    void 페이지를_나눠도_전체_건수는_정확하다() {
        for (int i = 1; i <= 5; i++) {
            Store store = persistStore("예시분식" + i, StoreStatus.OPEN, String.format("%010d", i));
            persistCheck(store, NtsLookupResult.CONFIRMED, BusinessState.CLOSED);
        }
        entityManager.clear();

        Page<StoreWithNtsCheck> firstPage = storeRepository.findStatusMismatch(PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    void 질의로_거른_결과가_응답_필드와_같다() {
        // 조회 실패(UNCONFIRMED)·번호 없음(NO_BIZ_NO)이어도 예전에 확인한 국세청 상태는 남아 있을 수 있다
        // (StoreNtsCheck.applyUnconfirmed/applyNoBizNo 가 ntsState 를 지우지 않는다). 그 조합까지 넣는다.
        StoreStatus[] statuses = {StoreStatus.OPEN, StoreStatus.SUSPENDED, StoreStatus.CLOSED, StoreStatus.UNKNOWN};
        BusinessState[] states = {null, BusinessState.ACTIVE, BusinessState.SUSPENDED, BusinessState.CLOSED,
                BusinessState.NOT_REGISTERED};
        int bizNo = 0;
        for (NtsLookupResult checkResult : NtsLookupResult.values()) {
            for (StoreStatus status : statuses) {
                for (BusinessState state : states) {
                    if (checkResult == NtsLookupResult.CONFIRMED && state == null) {
                        continue;   // 확인에 성공했는데 상태가 없는 기록은 만들어지지 않는다
                    }
                    Store store = persistStore("예시분식", status, String.format("%010d", ++bizNo));
                    persistCheck(store, checkResult, state);
                }
            }
        }
        entityManager.clear();

        List<StoreCheckResponse> all = storeRepository.findAllWithNtsCheck(PageRequest.of(0, 100)).getContent()
                .stream().map(StoreCheckResponse::from).toList();

        // 필터 결과 = 응답에서 해당 플래그가 true 인 가게. 어긋나면 "목록엔 불일치로 나오는데 필터엔 없는" 가게가 생긴다
        assertThat(storeIds(storeRepository.findStatusMismatch(PageRequest.of(0, 100))))
                .containsExactlyElementsOf(all.stream()
                        .filter(StoreCheckResponse::statusMismatch).map(StoreCheckResponse::storeId).toList());
        assertThat(storeIds(storeRepository.findDataProblem(PageRequest.of(0, 100))))
                .containsExactlyElementsOf(all.stream()
                        .filter(StoreCheckResponse::dataProblem).map(StoreCheckResponse::storeId).toList());
    }

    private static List<Long> storeIds(Page<StoreWithNtsCheck> page) {
        return page.getContent().stream().map(row -> row.store().getStoreId()).toList();
    }
}
