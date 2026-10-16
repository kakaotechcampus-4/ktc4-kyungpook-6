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
 * @param force    점주 연결·진행 중 조사가 있어도 지울지. 비우면 {@code false}
 */
public record StoreDeletionRequest(
        @Schema(description = "지울 가게 ID 목록", example = "[12, 13, 14]")
        @NotEmpty(message = "가게 ID 목록이 비어 있습니다")
        @Size(max = StoreDeletionRequest.MAX_STORES, message = "한 번에 "
                + StoreDeletionRequest.MAX_STORES + "건까지 보낼 수 있습니다")
        List<Long> storeIds,

        @Schema(description = """
                점주 연결이나 진행 중인 조사가 있어도 지울지. 기본은 false 입니다.

                **관리자가 그 리스크를 알고 지운다는 표시입니다.** true 면 점주 앱에서 그 가게가
                사라지고, 돌고 있는 조사의 대상이 없어집니다. 코드가 막는 대신 부른 쪽이
                분명히 밝히게 합니다.

                **아동 방문(체크인) 기록이 있는 가게는 true 여도 지우지 않습니다** — 보관할 방법을
                정하기 전까지는 지우는 쪽이 더 위험합니다.
                """, example = "false")
        Boolean force
) {

    /**
     * {@code force} 를 보내지 않았으면 {@code false} 로 본다.
     *
     * <p>{@code boolean} 이 아니라 {@code Boolean} 으로 받는 이유는, 안 보낸 것과 {@code false} 를
     * 구분할 필요는 없지만 <b>안 보냈을 때 역직렬화가 조용히 false 가 되는 것</b>에 기대지 않으려는
     * 것이다. 기본값을 여기 한 곳에서 정한다.
     *
     * @return 강제로 지울지
     */
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }

    /**
     * 한 요청으로 지울 수 있는 상한.
     *
     * <p>주입({@code MAX_STORES} 500)보다 작게 둔다. 지우기는 되돌릴 수 없어서, 한 번의 실수로
     * 사라지는 양을 작게 하는 편이 낫다. 더 많으면 나눠 보낸다.
     */
    public static final int MAX_STORES = 100;
}
