package com.ktc4.backend.domain.order.controller;

import com.ktc4.backend.domain.order.dto.ActiveOrderResponse;
import com.ktc4.backend.domain.order.dto.ChildOrderCreateRequest;
import com.ktc4.backend.domain.order.dto.OrderResponse;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "아동 주문", description = "아동이 주문하고 진행 중 주문을 보고 취소하는 API")
@RestController
@RequestMapping("/api/children/me/orders")
@RequiredArgsConstructor
public class ChildOrderController {

    private final OrderService orderService;

    @Operation(
            summary = "주문하기 (아동)",
            description = """
                    가게 메뉴 **하나**와 받는 방법(`TAKEOUT` 포장 · `DINE_IN` 매장 식사)을 골라 주문합니다. 아동 토큰을 붙입니다.
                    주문은 접수 대기(`RECEIVED`)로 시작합니다. 가게에 도착하면 내 QR 을 보여 주고, 점주가 확인하면 조리 중이 됩니다.

                    - 아이 한 명에게 진행 중(접수 대기·조리 중) 주문은 **하나뿐**입니다. 있으면 409 `active-order-exists` —
                      `GET /api/children/me/orders/active` 로 그 주문을 보여 주세요. 버튼을 두 번 눌러도 이 값입니다.
                    - 그 가게의 메뉴가 아니거나 지운 메뉴면 404 `menu-not-found`, 오늘 판매하지 않는 메뉴면 409 `menu-not-on-sale`
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "주문 접수"),
            @ApiResponse(responseCode = "400", description = "가게·메뉴·받는 방법이 없거나 형식이 틀린 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "아동 토큰이 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "그 가게의 주문할 수 있는 메뉴가 아닌 경우(menu-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "진행 중 주문이 있는 경우(active-order-exists), 판매하지 않는 메뉴(menu-not-on-sale)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@AuthenticationPrincipal AuthMember child,
                                @Valid @RequestBody ChildOrderCreateRequest request) {
        return orderService.create(AuthMember.requireChildId(child), request);
    }

    @Operation(
            summary = "진행 중 주문 (아동)",
            description = """
                    접수 대기·조리 중인 내 주문을 봅니다(내 QR 화면). 없으면 `order` 가 `null` 입니다 — 빈 본문이 아닙니다.
                    점주가 전달 완료하거나 주문이 취소되면 진행 중 주문이 아닙니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "아동 토큰이 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/active")
    public ActiveOrderResponse active(@AuthenticationPrincipal AuthMember child) {
        return new ActiveOrderResponse(orderService.findActive(AuthMember.requireChildId(child)).orElse(null));
    }

    @Operation(
            summary = "주문 취소 (아동)",
            description = "내 주문을 취소합니다. **접수 대기일 때만** 됩니다 — 조리를 시작한 뒤에는 409 `order-status-conflict`.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "취소 완료"),
            @ApiResponse(responseCode = "403", description = "아동 토큰이 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "내 주문이 아닌 경우(order-not-found) — 남의 주문인지 알려 주지 않습니다",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "접수 대기가 아닌 경우(order-status-conflict)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthMember child,
                                @Parameter(description = "주문 ID", example = "1024") @PathVariable Long orderId) {
        return orderService.cancelByChild(AuthMember.requireChildId(child), orderId);
    }
}
