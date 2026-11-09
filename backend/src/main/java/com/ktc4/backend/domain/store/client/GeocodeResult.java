package com.ktc4.backend.domain.store.client;

/**
 * 주소로 좌표를 찾은 결과. 못 찾은 것(주소가 틀림)과 묻지 못한 것(장애)을 가른다 — 앞쪽만 한동안 다시 묻지 않는다.
 */
public sealed interface GeocodeResult {

    /** 좌표를 찾았다. */
    record Found(double lat, double lng) implements GeocodeResult {
    }

    /** 물어봤지만 그 주소의 좌표를 얻지 못했다 — 주소가 틀렸거나 지도에 없거나, 카카오가 그 주소를 거절(400)했거나, 받은 좌표가 이상하다. */
    record NotFound() implements GeocodeResult {
    }

    /** 묻지 못했다 — 키가 틀렸거나, 시간 초과·서버 오류. 주소 탓이 아니라 다음에 다시 묻는다. */
    record Failed(String reason) implements GeocodeResult {
    }
}
