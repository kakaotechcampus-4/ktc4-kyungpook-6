package com.ktc4.backend.domain.job.dto;

import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** 조사를 시작할 때 저장하는 대상 가게의 표시 정보. 조사 전 목록을 새로고침 후에도 보여 준다. */
public record JobTargetStore(
        @Schema(description = "가게 ID") Long storeId,
        @Schema(description = "조사 시작 당시 상호") String storeName,
        @Schema(description = "조사 시작 당시 도로명 주소") String storeAddress,
        @Schema(description = "조사 시작 당시 전화번호", nullable = true) String storePhone
) {
    public static JobTargetStore from(StoreCheckResponse store) {
        return new JobTargetStore(store.storeId(), store.name(), store.addressRoad(), store.phone());
    }
}
