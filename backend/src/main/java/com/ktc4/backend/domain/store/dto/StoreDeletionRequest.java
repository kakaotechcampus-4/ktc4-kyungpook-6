package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 가게 여러 곳을 지우는 요청.
 *
 * <p>{@code DELETE} 로 목록을 받지 않고 {@code POST /api/admin/stores/deletions} 로 둔 이유는 두 가지다.
 * {@code DELETE} 에 본문을 싣는 것은 중간 장비·클라이언트에 따라 조용히 버려질 수 있고,
 * {@code POST /imports} 와 모양을 맞추면 넣는 길과 지우는 길이 같은 자리에서 보인다.
 *
 * <p>목록을 쓰는 이유는 되돌릴 일이 보통 여러 건이기 때문이다 — 잘못 넣은 명단, 한 바퀴 돌려 보려고
 * 넣은 가공 자료는 한 곳이 아니라 묶음으로 생긴다.
 *
 * @param storeIds 지울 가게 ID. 같은 ID 가 여러 번 있으면 한 번만 처리한다
 */
public record StoreDeletionRequest(
        @Schema(description = "지울 가게 ID 목록", example = "[12, 13, 14]")
        @NotEmpty(message = "가게 ID 목록이 비어 있습니다")
        @Size(max = StoreDeletionRequest.MAX_STORES, message = "한 번에 "
                + StoreDeletionRequest.MAX_STORES + "건까지 보낼 수 있습니다")
        List<Long> storeIds
) {

    /**
     * 한 요청으로 지울 수 있는 상한.
     *
     * <p>주입({@code MAX_STORES} 500)보다 작게 둔다. 지우기는 되돌릴 수 없어서, 한 번의 실수로
     * 사라지는 양을 작게 하는 편이 낫다. 더 많으면 나눠 보낸다.
     */
    public static final int MAX_STORES = 100;
}
