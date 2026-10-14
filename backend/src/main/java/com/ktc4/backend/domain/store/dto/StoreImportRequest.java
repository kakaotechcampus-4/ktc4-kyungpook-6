package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 가게 자료 주입 요청 DTO.
 *
 * <p>최상위를 배열이 아니라 객체로 둔 이유는 두 가지다. 건수 상한을 요청 자체에 걸 수 있고
 * ({@code @Size}), 나중에 옵션(예: 이미 있는 가게를 덮어쓸지)이 생겨도 요청 모양을 바꾸지 않는다.
 *
 * <p>{@value #MAX_STORES}건은 한 요청의 상한일 뿐이다. 더 많으면 호출자가 나눠 보낸다 —
 * 같은 가게를 다시 보내도 늘어나지 않으므로(이름·주소 정규화 값으로 건너뛴다) 나눠 보내도 안전하다.
 */
public record StoreImportRequest(
        @Schema(description = "주입할 가게 목록")
        @NotEmpty(message = "가게 목록이 비어 있습니다")
        @Size(max = StoreImportRequest.MAX_STORES, message = "한 번에 "
                + StoreImportRequest.MAX_STORES + "건까지 보낼 수 있습니다")
        @Valid
        List<StoreImportItem> stores
) {

    /** 한 요청에 담을 수 있는 최대 건수. */
    public static final int MAX_STORES = 500;
}
