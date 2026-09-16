package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.service.BusinessLookupService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

// 가짜 객체가 아니라 실제 DB(인메모리 H2)에 저장·수정이 반영되는지 확인한다.
// 국세청 호출은 이 테스트의 관심사가 아니라 가짜로 둔다.
@DataJpaTest
@Import({StoreService.class, JpaAuditingConfig.class})
@DisplayName("StoreService - DB 연동")
class StoreServiceDbTest {

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    @MockitoBean
    private BusinessLookupService businessLookupService;

    private Store save(String name, String nameNormalized,
                       String addressRoad, String addressNormalized, String bizNo) {
        return storeRepository.save(Store.builder()
                .name(name)
                .nameNormalized(nameNormalized)
                .addressRoad(addressRoad)
                .addressNormalized(addressNormalized)
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .build());
    }

    @Test
    @DisplayName("저장한 가게에 생성·수정 시각이 자동으로 채워진다")
    void fillsAuditingColumns() {
        Store saved = save("예시분식", "예시분식", "가상특별시 예시구 샘플로 123", "가상특별시예시구샘플로123", "1234567890");

        assertThat(saved.getStoreId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("renormalizeAll 이 계산한 값이 DB 에 실제로 반영된다")
    void renormalizeAllPersistsChanges() {
        // 워드에서 붙여넣은 듯한 en dash(U+2013) 주소를 그대로 넣어둔다
        String addressWithEnDash = "가상특별시 예시구 샘플로 80" + (char) 0x2013 + "1";
        Long storeId = save("(주)예시분식", "옛날값",
                addressWithEnDash, "옛날값", "123-45-67890").getStoreId();
        entityManager.flush();
        entityManager.clear();

        int changed = storeService.renormalizeAll();
        entityManager.flush();
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        Store reloaded = storeRepository.findById(storeId).orElseThrow();
        assertThat(reloaded.getNameNormalized()).isEqualTo("예시분식");
        assertThat(reloaded.getAddressNormalized()).isEqualTo("가상특별시예시구샘플로80-1");
        assertThat(reloaded.getBizNo()).isEqualTo("1234567890");
        // 원본은 그대로 둔다
        assertThat(reloaded.getName()).isEqualTo("(주)예시분식");
        assertThat(reloaded.getAddressRoad()).isEqualTo(addressWithEnDash);
    }

    @Test
    @DisplayName("이미 정규화된 가게는 바뀐 것으로 세지 않는다")
    void doesNotCountUnchangedStore() {
        save("예시분식", "예시분식", "샘플로 1", "샘플로1", "1234567890");
        entityManager.flush();
        entityManager.clear();

        assertThat(storeService.renormalizeAll()).isZero();
    }

}
