package com.ktc4.backend.domain.store.service;

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

import static org.mockito.Mockito.verify;

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

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @DisplayName("휴대폰 번호 없이 가입한 점주는 전화번호로는 찾지 않는다 — 빈 값으로 찾으면 전화번호가 빈 가게가 후보가 된다")
    void skipsPhoneWhenMissing(String phone) {
        storeService.findOwnerCandidates(BIZ_NO, phone, "예시분식");

        verify(storeRepository).findOwnerCandidates(
                BIZ_NO, StoreService.NO_MATCH, "예시분식", StoreService.MAX_OWNER_CANDIDATES);
    }
}
