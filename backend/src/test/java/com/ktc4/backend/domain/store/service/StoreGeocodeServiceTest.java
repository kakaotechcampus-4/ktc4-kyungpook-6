package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.store.client.AddressGeocoder;
import com.ktc4.backend.domain.store.client.GeocodeResult;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌표 채우기를 실제 Postgres 로 본다 — 대상 고르기(좌표 없음·폐업 제외·최근 못 찾음 건너뜀)와 결과 기록. 카카오는 가짜다.
 * 주소·좌표는 지어낸 값이다.
 */
@Import({StoreGeocodeService.class, StoreGeocodeServiceTest.FakeGeocoderConfig.class})
@TestPropertySource(properties = {"geocode.batch-size=3", "geocode.retry-after-days=7"})
@DisplayName("StoreGeocodeService (Postgres)")
class StoreGeocodeServiceTest extends PostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 12, 0);

    @Autowired
    private StoreGeocodeService service;

    @Autowired
    private FakeGeocoder geocoder;

    @Autowired
    private TestEntityManager entityManager;

    @TestConfiguration
    static class FakeGeocoderConfig {
        @Bean
        FakeGeocoder fakeGeocoder() {
            return new FakeGeocoder();
        }
    }

    // 주소별로 정해 둔 답을 돌려주고(던질 주소면 예외), 물어본 주소를 순서대로 남긴다
    static class FakeGeocoder implements AddressGeocoder {
        final Map<String, GeocodeResult> answers = new HashMap<>();
        final Set<String> throwing = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        boolean enabled = true;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public GeocodeResult geocode(String address) {
            asked.add(address);
            if (throwing.contains(address)) {
                throw new IllegalArgumentException("예상 못 한 예외");
            }
            return answers.getOrDefault(address, new GeocodeResult.NotFound());
        }
    }

    @BeforeEach
    void reset() {
        geocoder.answers.clear();
        geocoder.throwing.clear();
        geocoder.asked.clear();
        geocoder.enabled = true;
    }

    private Store store(String address, Double lat, Double lng, StoreStatus status) {
        return entityManager.persistAndFlush(Store.builder()
                .name("예시분식").nameNormalized("예시분식")
                .addressRoad(address).addressNormalized(address.replace(" ", ""))
                .lat(lat).lng(lng).status(status)
                .build());
    }

    private Store reload(Store store) {
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(Store.class, store.getStoreId());
    }

    @Test
    @DisplayName("좌표가 없는 가게를 주소로 채운다 — 좌표가 있는 가게·폐업 가게는 묻지 않는다")
    void fillsStoresWithoutCoordinates() {
        Store missing = store("가상특별시 예시구 샘플로 1", null, null, StoreStatus.UNKNOWN);
        store("가상특별시 예시구 샘플로 2", 35.1, 128.1, StoreStatus.OPEN);
        store("가상특별시 예시구 샘플로 3", null, null, StoreStatus.CLOSED);
        geocoder.answers.put("가상특별시 예시구 샘플로 1", new GeocodeResult.Found(35.8884, 128.6107));

        StoreGeocodeService.Summary summary = service.fillMissingCoordinates(NOW);

        assertThat(geocoder.asked).containsExactly("가상특별시 예시구 샘플로 1");
        assertThat(summary.found()).isEqualTo(1);
        Store filled = reload(missing);
        assertThat(filled.getLat()).isEqualTo(35.8884);
        assertThat(filled.getLng()).isEqualTo(128.6107);
        assertThat(filled.getGeocodeAttemptedAt()).isNull();
    }

    @Test
    @DisplayName("못 찾은 주소는 시각을 남기고 7일 동안 다시 묻지 않는다 — 7일이 지나면 다시 묻는다")
    void missIsRecordedAndRetriedAfterSevenDays() {
        Store store = store("가상특별시 없는구 없는로 9", null, null, StoreStatus.OPEN);

        service.fillMissingCoordinates(NOW);
        assertThat(reload(store).getGeocodeAttemptedAt()).isEqualTo(NOW);

        geocoder.asked.clear();
        service.fillMissingCoordinates(NOW.plusDays(6));
        assertThat(geocoder.asked).isEmpty();

        service.fillMissingCoordinates(NOW.plusDays(7).plusMinutes(1));
        assertThat(geocoder.asked).containsExactly("가상특별시 없는구 없는로 9");
    }

    @Test
    @DisplayName("묻지 못하면(키·장애) 기록하지 않고 이번 회차를 멈춘다 — 다음 주기에 다시 묻는다")
    void failureStopsTheRunWithoutRecording() {
        Store first = store("가상특별시 예시구 샘플로 1", null, null, StoreStatus.OPEN);
        store("가상특별시 예시구 샘플로 2", null, null, StoreStatus.OPEN);
        geocoder.answers.put("가상특별시 예시구 샘플로 1", new GeocodeResult.Failed("카카오가 500 로 답했습니다"));

        StoreGeocodeService.Summary summary = service.fillMissingCoordinates(NOW);

        assertThat(summary.stopped()).isTrue();
        assertThat(geocoder.asked).containsExactly("가상특별시 예시구 샘플로 1");
        assertThat(reload(first).getGeocodeAttemptedAt()).isNull();
    }

    @Test
    @DisplayName("예상 못 한 예외가 나면 그 가게만 건너뛰고 다음 가게를 채운다 — 한 가게가 배치 전체를 막지 않는다")
    void unexpectedExceptionSkipsOnlyThatStore() {
        Store broken = store("가상특별시 예시구 샘플로 1", null, null, StoreStatus.OPEN);
        Store next = store("가상특별시 예시구 샘플로 2", null, null, StoreStatus.OPEN);
        geocoder.throwing.add("가상특별시 예시구 샘플로 1");
        geocoder.answers.put("가상특별시 예시구 샘플로 2", new GeocodeResult.Found(35.8884, 128.6107));

        StoreGeocodeService.Summary summary = service.fillMissingCoordinates(NOW);

        assertThat(summary.stopped()).isFalse();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.found()).isEqualTo(1);
        assertThat(reload(broken).getGeocodeAttemptedAt()).isNull();
        assertThat(reload(next).getLat()).isEqualTo(35.8884);
    }

    @Test
    @DisplayName("주소가 비어 있는 가게는 묻지 않는다")
    void skipsBlankAddress() {
        store("  ", null, null, StoreStatus.OPEN);

        StoreGeocodeService.Summary summary = service.fillMissingCoordinates(NOW);

        assertThat(summary.targets()).isZero();
        assertThat(geocoder.asked).isEmpty();
    }

    @Test
    @DisplayName("한 회차에 batch-size 곳까지만, 오래된 가게부터 묻는다")
    void respectsBatchSize() {
        for (int i = 1; i <= 5; i++) {
            store("가상특별시 예시구 샘플로 " + i, null, null, StoreStatus.OPEN);
        }

        service.fillMissingCoordinates(NOW);

        assertThat(geocoder.asked).containsExactly(
                "가상특별시 예시구 샘플로 1", "가상특별시 예시구 샘플로 2", "가상특별시 예시구 샘플로 3");
    }

    @Test
    @DisplayName("키가 없으면 아무것도 묻지 않는다")
    void doesNothingWhenDisabled() {
        store("가상특별시 예시구 샘플로 1", null, null, StoreStatus.OPEN);
        geocoder.enabled = false;

        assertThat(service.fillMissingCoordinates(NOW).targets()).isZero();
        assertThat(geocoder.asked).isEmpty();
    }
}
