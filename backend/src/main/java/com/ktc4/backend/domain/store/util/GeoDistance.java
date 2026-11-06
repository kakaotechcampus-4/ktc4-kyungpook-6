package com.ktc4.backend.domain.store.util;

/**
 * 위경도 두 점 사이의 거리와, 한 점을 둘러싼 위경도 사각형.
 *
 * <p>주변 가게는 DB 에서 사각형으로 먼저 거르고(인덱스를 탄다) 그 안에서 실제 거리로 다시 거른다. PostGIS 없이 쓰는
 * 지금 규모(가게 수천 곳)에서는 충분하다.
 *
 * <p>날짜 변경선(경도 ±180)을 넘는 사각형은 다루지 않는다 — 서비스 지역이 국내라 생기지 않는다.
 */
public final class GeoDistance {

    // 지구 평균 반지름(IUGG). 위도 1도의 길이도 이 값에서 구해, 사각형이 거리 기준보다 좁아지지 않게 한다
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final double METERS_PER_DEGREE = Math.PI * EARTH_RADIUS_METERS / 180;
    // 극 가까이에서는 경도 1도가 0m 에 가까워져 사각형이 무한히 넓어진다 — 그 전에 경도 전체로 둔다
    private static final double MIN_COS_LATITUDE = 1e-6;

    private GeoDistance() {
    }

    /**
     * 두 점 사이의 거리(m) — 하버사인 공식.
     */
    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * 한 점에서 반경 안의 점을 모두 담는 위경도 사각형. 사각형 모서리는 반경 밖이라 거리로 한 번 더 걸러야 한다.
     *
     * @param lat          가운데 위도
     * @param lng          가운데 경도
     * @param radiusMeters 반경(m)
     */
    public static Box boxAround(double lat, double lng, double radiusMeters) {
        double dLat = radiusMeters / METERS_PER_DEGREE;
        double cos = Math.cos(Math.toRadians(lat));
        double dLng = cos < MIN_COS_LATITUDE ? 180 : radiusMeters / (METERS_PER_DEGREE * cos);
        return new Box(Math.max(-90, lat - dLat), Math.min(90, lat + dLat),
                Math.max(-180, lng - dLng), Math.min(180, lng + dLng));
    }

    /** 위경도 사각형 — 양 끝을 포함한다. */
    public record Box(double minLat, double maxLat, double minLng, double maxLng) {
    }
}
