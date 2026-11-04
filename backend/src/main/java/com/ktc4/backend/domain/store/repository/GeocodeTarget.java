package com.ktc4.backend.domain.store.repository;

/**
 * 좌표 채우기 대상 한 곳 — 가게 ID 와 그때의 도로명 주소.
 *
 * @param storeId     가게 ID
 * @param addressRoad 카카오에 물을 도로명 주소. 묻는 사이 주소가 바뀌면 받은 좌표를 버린다
 */
public record GeocodeTarget(Long storeId, String addressRoad) {
}
