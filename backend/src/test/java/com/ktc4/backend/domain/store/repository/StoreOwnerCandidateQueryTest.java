package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 점주 가입 신청에 연결할 후보 가게 질의 통합 테스트. 네이티브 질의라 실제 Postgres 로 확인한다.
 * 가게 정보·번호는 모두 가짜 값이다.
 */
class StoreOwnerCandidateQueryTest extends PostgresContainerTest {

    private static final String BIZ_NO = "1111111111";
    private static final String PHONE = "01000000001";
    private static final String NAME = "예시분식";
    // StoreService.NO_MATCH 와 같은 값 — "이 단서로는 찾지 않는다"
    private static final String NO_MATCH = "#";
    private static final int LIMIT = 20;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store persistStore(String nameNormalized, String bizNo, String phone) {
        return entityManager.persistAndFlush(Store.builder()
                .name(nameNormalized)
                .nameNormalized(nameNormalized)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .phone(phone)
                .build());
    }

    private List<Long> candidateIds(String bizNo, String phone, String nameNormalized, int limit) {
        entityManager.clear();
        return storeRepository.findOwnerCandidates(bizNo, phone, nameNormalized, limit).stream()
                .map(Store::getStoreId)
                .toList();
    }

    @Test
    void 사업자번호_전화번호_이름_순서로_두고_무관한_가게는_뺀다() {
        Store nameOnly = persistStore("예시분식본점", null, null);
        Store unrelated = persistStore("샘플카페", "2222222222", "01000000002");
        Store phoneOnly = persistStore("전혀다른이름", null, "01000000001");
        Store bizNoOnly = persistStore("또다른이름", "1111111111", null);

        assertThat(candidateIds(BIZ_NO, PHONE, NAME, LIMIT))
                .containsExactly(bizNoOnly.getStoreId(), phoneOnly.getStoreId(), nameOnly.getStoreId())
                .doesNotContain(unrelated.getStoreId());
    }

    @Test
    void 여러_단서가_맞는_가게는_한_번만_나오고_가장_확실한_단서의_자리에_선다() {
        Store nameOnly = persistStore("예시분식2호점", null, null);
        Store everything = persistStore("예시분식", "1111111111", "01000000001");

        assertThat(candidateIds(BIZ_NO, PHONE, NAME, LIMIT))
                .containsExactly(everything.getStoreId(), nameOnly.getStoreId());
    }

    @Test
    void 가게_번호에_하이픈이나_공백이_섞여_있어도_같은_번호로_찾는다() {
        Store hyphenBizNo = persistStore("가나다", "111-11-11111", null);
        Store hyphenPhone = persistStore("라마바", null, "010-0000-0001");
        Store spacedPhone = persistStore("사아자", null, "010 0000 0001");

        assertThat(candidateIds(BIZ_NO, PHONE, NO_MATCH, LIMIT))
                .containsExactly(hyphenBizNo.getStoreId(), hyphenPhone.getStoreId(), spacedPhone.getStoreId());
    }

    @Test
    void 이름이_정확히_같은_가게를_일부만_겹치는_가게보다_앞에_둔다() {
        // 흔한 이름이면 겹치는 가게가 많다. storeId 순으로만 두면 정확히 같은 가게가 건수 제한에 잘릴 수 있다
        for (int i = 0; i < 3; i++) {
            persistStore("대구예시분식" + i + "호점", null, null);
        }
        Store exact = persistStore("예시분식", null, null);

        List<Long> candidates = candidateIds(BIZ_NO, PHONE, NAME, 2);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).isEqualTo(exact.getStoreId());
    }

    @Test
    void 이름은_일부만_겹쳐도_후보가_된다() {
        Store longer = persistStore("대구예시분식2호점", null, null);

        assertThat(candidateIds(BIZ_NO, PHONE, NAME, LIMIT)).containsExactly(longer.getStoreId());
    }

    @Test
    void 번호가_비어_있는_가게는_번호로는_후보가_되지_않는다() {
        persistStore("가나다", null, null);
        persistStore("라마바", "", "");

        assertThat(candidateIds(BIZ_NO, PHONE, NO_MATCH, LIMIT)).isEmpty();
    }

    @Test
    void 찾지_않을_단서에_넘기는_값은_번호가_빈_가게와도_어떤_이름과도_맞지_않는다() {
        // 빈 문자열을 넘겼다면 번호가 빈 가게, 이름에서는 모든 가게가 후보가 됐을 것이다
        persistStore("가나다", null, "");
        persistStore("라마바", "", null);
        Store bizNoMatched = persistStore("사아자", "1111111111", null);

        assertThat(candidateIds(BIZ_NO, NO_MATCH, NO_MATCH, LIMIT)).containsExactly(bizNoMatched.getStoreId());
    }

    @Test
    void 요청한_건수까지만_돌려주고_번호가_맞는_가게가_잘리지_않는다() {
        for (int i = 0; i < 5; i++) {
            persistStore("예시분식" + i + "호점", null, null);
        }
        Store phoneMatched = persistStore("전혀다른이름", null, "01000000001");
        Store bizNoMatched = persistStore("또다른이름", "1111111111", null);

        // 번호가 맞는 가게는 storeId 가 가장 커도 앞에 서므로 건수 제한에 잘리지 않는다
        List<Long> candidates = candidateIds(BIZ_NO, PHONE, NAME, 3);

        assertThat(candidates).hasSize(3);
        assertThat(candidates.subList(0, 2)).containsExactly(bizNoMatched.getStoreId(), phoneMatched.getStoreId());
    }
}
