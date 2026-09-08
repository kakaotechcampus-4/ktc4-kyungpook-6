package com.ktc4.backend.store;

import java.time.LocalDateTime;

/**
 * 가게 정보 응답 DTO.
 *
 * <p>{@link Store} 엔티티를 그대로 내보내지 않고 이 타입으로 감싸는 이유는 두 가지다.
 * 하나는 엔티티에 컬럼이 추가돼도 API 응답이 멋대로 바뀌지 않게 하기 위해서고,
 * 다른 하나는 내부 전용 값을 노출하지 않기 위해서다.
 *
 * <p>그래서 {@code nameNormalized} / {@code addressNormalized} 는 의도적으로 제외했다.
 * 비교·매칭에만 쓰는 내부 값이라 화면에서는 쓸 일이 없다.
 */
public record StoreResponse(
        Long storeId,
        String name,
        String addressRoad,
        Double lat,
        Double lng,
        Store.Status status,
        String category,
        String phone,
        String bizNo,
        LocalDateTime lastCheckedAt
) {

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getAddressRoad(),
                store.getLat(),
                store.getLng(),
                store.getStatus(),
                store.getCategory(),
                store.getPhone(),
                store.getBizNo(),
                store.getLastCheckedAt()
        );
    }
}
