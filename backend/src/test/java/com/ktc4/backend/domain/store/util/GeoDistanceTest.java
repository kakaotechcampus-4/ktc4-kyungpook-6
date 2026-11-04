package com.ktc4.backend.domain.store.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("GeoDistance")
class GeoDistanceTest {

    // 대구 북구 근처 — 서비스 지역의 위도
    private static final double LAT = 35.8884;
    private static final double LNG = 128.6107;

    @Test
    @DisplayName("같은 점은 0m, 위도 0.01도는 약 1.11km")
    void measuresKnownDistances() {
        assertThat(GeoDistance.meters(LAT, LNG, LAT, LNG)).isZero();
        assertThat(GeoDistance.meters(LAT, LNG, LAT + 0.01, LNG)).isCloseTo(1112, within(2.0));
    }

    @Test
    @DisplayName("사각형은 반경 끝의 점을 남북·동서 모두 담는다 — 사각형이 좁으면 경계의 가게가 빠진다")
    void boxContainsPointsAtTheRadius() {
        int radius = 2000;
        GeoDistance.Box box = GeoDistance.boxAround(LAT, LNG, radius);

        // 반경과 같은 거리의 북쪽·동쪽 점을 사각형 경계에서 거꾸로 구한다 — 거리가 반경 이상이어야 한다
        assertThat(GeoDistance.meters(LAT, LNG, box.maxLat(), LNG)).isGreaterThanOrEqualTo(radius - 0.5);
        assertThat(GeoDistance.meters(LAT, LNG, LAT, box.maxLng())).isGreaterThanOrEqualTo(radius - 0.5);
        assertThat(box.minLat()).isLessThan(LAT);
        assertThat(box.minLng()).isLessThan(LNG);
    }

    @Test
    @DisplayName("극 가까이에서도 사각형이 위경도 범위를 넘지 않는다")
    void boxStaysWithinBounds() {
        GeoDistance.Box box = GeoDistance.boxAround(89.9999, 179.99, 5000);

        assertThat(box.maxLat()).isLessThanOrEqualTo(90);
        assertThat(box.minLng()).isGreaterThanOrEqualTo(-180);
        assertThat(box.maxLng()).isLessThanOrEqualTo(180);
    }
}
