package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 점주에게 연결된 가게 한 곳. 관리자가 연결을 끊을 대상을 고르는 데 쓴다.
 *
 * <p>끊긴 연결은 담지 않는다 — 지금 체크인할 수 있는 가게만 보여야 "무엇을 끊을지" 고를 수 있다.
 */
public record LinkedStoreResponse(
        @Schema(description = "가게 ID — 연결 끊기 API 에 쓴다", example = "123") Long storeId,
        @Schema(description = "가게명", example = "예시분식") String name,
        @Schema(description = "도로명 주소. 지점을 구분하는 단서다") String addressRoad,
        @Schema(description = "이 점주와 연결한 시각") LocalDateTime linkedAt
) {
    public static LinkedStoreResponse from(StoreOwnerRepository.LinkedStoreDetail detail) {
        return new LinkedStoreResponse(
                detail.getStoreId(), detail.getName(), detail.getAddressRoad(), detail.getLinkedAt());
    }
}
