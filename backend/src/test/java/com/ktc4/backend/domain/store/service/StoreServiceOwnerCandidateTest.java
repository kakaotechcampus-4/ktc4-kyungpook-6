package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// DB 조회를 가짜(Mockito)로 바꿔, 후보를 찾을 때 단서를 어떻게 넘기는지만 검증한다. 번호는 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreService — 점주 후보 가게")
class StoreServiceOwnerCandidateTest {

    private static final String BIZ_NO = "1234567890";
    private static final String PHONE = "01000000000";

    @Mock
    private StoreRepository storeRepository;

    @InjectMocks
    private StoreService storeService;

    @Test
    @DisplayName("상호명을 가게 이름과 같은 규칙으로 정규화해 찾는다 — 표기가 달라도 같은 가게가 후보에 든다")
    void normalizesStoreName() {
        storeService.findOwnerCandidates(BIZ_NO, PHONE, "(주) 예시 분식!");

        verify(storeRepository).findOwnerCandidates(BIZ_NO, PHONE, "예시분식", StoreService.MAX_OWNER_CANDIDATES);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"!!!", "★☆", "(주)", "_", "%", "%_%"})
    @DisplayName("정규화하고 남는 글자가 없으면 이름으로는 찾지 않는다 — 빈 이름으로 찾으면 모든 가게가 후보가 된다 (LIKE 특수문자도 지워진다)")
    void skipsNameWhenNothingLeft(String storeName) {
        storeService.findOwnerCandidates(BIZ_NO, PHONE, storeName);

        verify(storeRepository).findOwnerCandidates(
                BIZ_NO, PHONE, StoreService.NO_MATCH, StoreService.MAX_OWNER_CANDIDATES);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"밥", "(주) 밥!", "a"})
    @DisplayName("정규화하고 한 글자만 남으면 이름으로는 찾지 않는다 — 사실상 모든 가게와 겹쳐 맞는 가게가 잘린다")
    void skipsOneLetterName(String storeName) {
        storeService.findOwnerCandidates(BIZ_NO, PHONE, storeName);

        verify(storeRepository).findOwnerCandidates(
                BIZ_NO, PHONE, StoreService.NO_MATCH, StoreService.MAX_OWNER_CANDIDATES);
    }

    @Test
    @DisplayName("두 글자부터는 이름으로 찾는다")
    void searchesTwoLetterName() {
        storeService.findOwnerCandidates(BIZ_NO, PHONE, "밥집");

        verify(storeRepository).findOwnerCandidates(BIZ_NO, PHONE, "밥집", StoreService.MAX_OWNER_CANDIDATES);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @DisplayName("사업자번호가 비어 있으면 번호로는 찾지 않는다 — 빈 값으로 찾으면 번호가 빈 가게가 전부 1순위 후보가 된다")
    void skipsBizNoWhenMissing(String bizNo) {
        storeService.findOwnerCandidates(bizNo, PHONE, "예시분식");

        verify(storeRepository).findOwnerCandidates(
                StoreService.NO_MATCH, PHONE, "예시분식", StoreService.MAX_OWNER_CANDIDATES);
    }

    // ── 후보 가게의 국세청 상태 ─────────────────────────────────────────────

    private static Store store(long storeId) {
        Store store = Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .build();
        ReflectionTestUtils.setField(store, "storeId", storeId);
        return store;
    }

    private static StoreWithNtsCheck row(long storeId, NtsLookupResult checkResult, BusinessState ntsState) {
        Store store = store(storeId);
        return new StoreWithNtsCheck(store, StoreNtsCheck.builder()
                .store(store)
                .checkResult(checkResult)
                .ntsState(ntsState)
                .lastAttemptAt(LocalDateTime.of(2026, 10, 1, 3, 0))
                .build());
    }

    @Test
    @DisplayName("국세청 상태는 배치가 확인해 둔 값을 읽는다 — 확인 전이거나 번호가 지워진 가게는 결과에 없다")
    void findsNtsStates() {
        List<Long> storeIds = List.of(1L, 2L, 3L, 4L, 5L);
        when(storeRepository.findWithNtsCheckByStoreIdIn(storeIds)).thenReturn(List.of(
                row(1L, NtsLookupResult.CONFIRMED, BusinessState.CLOSED),
                // 이번 조회는 실패했어도 마지막으로 확인한 상태는 남아 있다
                row(2L, NtsLookupResult.UNCONFIRMED, BusinessState.ACTIVE),
                // 번호가 지워진 가게에 남은 상태는 옛 번호 기준이라 다른 사업자의 것일 수 있다
                row(3L, NtsLookupResult.NO_BIZ_NO, BusinessState.ACTIVE),
                row(4L, NtsLookupResult.UNCONFIRMED, null),
                new StoreWithNtsCheck(store(5L), null)));

        assertThat(storeService.findNtsStates(storeIds))
                .containsOnly(entry(1L, BusinessState.CLOSED), entry(2L, BusinessState.ACTIVE));
    }

    @Test
    @DisplayName("가게가 없으면 조회하지 않고 빈 결과를 준다")
    void findsNoNtsStatesForNoStores() {
        assertThat(storeService.findNtsStates(List.of())).isEmpty();

        verifyNoInteractions(storeRepository);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @DisplayName("휴대폰 번호 없이 가입한 점주는 휴대폰 번호로는 찾지 않는다 — 빈 값으로 찾으면 점주 휴대폰 번호가 빈 가게가 후보가 된다")
    void skipsPhoneWhenMissing(String phone) {
        storeService.findOwnerCandidates(BIZ_NO, phone, "예시분식");

        verify(storeRepository).findOwnerCandidates(
                BIZ_NO, StoreService.NO_MATCH, "예시분식", StoreService.MAX_OWNER_CANDIDATES);
    }
}
