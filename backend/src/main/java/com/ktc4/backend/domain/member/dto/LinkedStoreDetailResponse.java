package com.ktc4.backend.domain.member.dto;

import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 점주에게 연결된 가게 한 곳. 관리자가 연결을 끊을 대상을 고르는 데 쓴다.
 *
 * <p>끊긴 연결은 담지 않는다 — 지금 체크인할 수 있는 가게만 보여야 "무엇을 끊을지" 고를 수 있다.
 *
 * <p><b>이름에 Detail 이 붙은 이유</b>: 점주 앱이 보는
 * {@link com.ktc4.backend.domain.auth.dto.LinkedStoreResponse}(가게 ID·이름만)와 다른 칸을 담는데,
 * 처음에는 둘 다 {@code LinkedStoreResponse} 였다. springdoc 은 스키마 이름을 **클래스 단순 이름**으로
 * 짓기 때문에 둘이 한 이름으로 겹쳐, 먼저 잡힌 쪽이 남고 이쪽은 통째로 덮였다 — 스웨거에
 * {@code /api/admin/owners/{memberId}/stores} 응답이 {@code storeId}·{@code name} 두 칸으로만 나왔고,
 * 거기서 코드를 만드는 프론트에는 {@code addressRoad}·{@code linkedAt} 이 아예 없는 타입이 생겼다.
 * 패키지가 달라도 스웨거에서는 같은 이름이 되므로, DTO 이름은 저장소 전체에서 겹치지 않게 짓는다.
 */
public record LinkedStoreDetailResponse(
        @Schema(description = "가게 ID — 연결 끊기 API 에 쓴다", example = "123") Long storeId,
        @Schema(description = "가게명", example = "예시분식") String name,
        @Schema(description = "도로명 주소. 지점을 구분하는 단서다") String addressRoad,
        @Schema(description = "이 점주와 연결한 시각") LocalDateTime linkedAt
) {
    public static LinkedStoreDetailResponse from(StoreOwnerRepository.LinkedStoreDetail detail) {
        return new LinkedStoreDetailResponse(
                detail.getStoreId(), detail.getName(), detail.getAddressRoad(), detail.getLinkedAt());
    }
}
