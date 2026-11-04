package com.ktc4.backend.domain.order.controller;

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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "가게 주문", description = "점주가 가게 주문을 보고 조리 시작·완료·취소하는 API")
@RestController
@RequestMapping("/api/stores/{storeId}/orders")
@RequiredArgsConstructor
public class StoreOrderController {

    private final OrderService orderService;

    @Operation(
            summary = "가게 주문 목록 (점주)",
            description = """
                    이 가게의 **오늘(한국 날짜) 주문 전부**와, 날짜와 상관없이 **아직 끝나지 않은 주문**(접수 대기·조리 중)을
                    최근 순으로 줍니다. 탭(접수 대기·조리 중·완료)과 건수는 `status` 로 나눠 주세요. 오늘 취소된 주문(`CANCELED`)도 실립니다.

                    ⚠️ 조리 중인 주문은 **"조리 완료"를 눌러야** 끝납니다. 누르지 않으면 그 아이는 다음 주문을 할 수 없습니다.
                    점주는 연결된 가게만 볼 수 있습니다(아니면 403).
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping
    public List<OrderResponse> list(@Parameter(description = "가게 ID", example = "10") @PathVariable Long storeId,
                                    @AuthenticationPrincipal AuthMember authMember) {
        return orderService.listForStore(storeId, authMember);
    }

    @Operation(
            summary = "조리 시작 (점주)",
            description = """
                    접수 대기 주문의 조리를 시작합니다. 아이의 방문을 확인한 뒤 부릅니다 — QR 체크인 결과 화면의 "조리 시작하기"와
                    주문 화면의 "직접 확인"(주문번호로 확인) 둘 다 이 API 입니다. 접수 대기가 아니면 409 `order-status-conflict`.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조리 중으로 바뀜"),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "이 가게의 주문이 아닌 경우(order-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "접수 대기가 아닌 경우(order-status-conflict)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{orderId}/start-cooking")
    public OrderResponse startCooking(@Parameter(description = "가게 ID", example = "10") @PathVariable Long storeId,
                                      @Parameter(description = "주문 ID", example = "1024") @PathVariable Long orderId,
                                      @AuthenticationPrincipal AuthMember authMember) {
        return orderService.startCooking(storeId, orderId, authMember);
    }

    @Operation(summary = "조리 완료 (점주)",
            description = "조리 중 주문을 전달 완료로 바꿉니다. 조리 중이 아니면 409 `order-status-conflict`.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "전달 완료로 바뀜"),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "이 가게의 주문이 아닌 경우(order-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "조리 중이 아닌 경우(order-status-conflict)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{orderId}/complete")
    public OrderResponse complete(@Parameter(description = "가게 ID", example = "10") @PathVariable Long storeId,
                                  @Parameter(description = "주문 ID", example = "1024") @PathVariable Long orderId,
                                  @AuthenticationPrincipal AuthMember authMember) {
        return orderService.complete(storeId, orderId, authMember);
    }

    @Operation(summary = "주문 취소 (점주)",
            description = "접수 대기 주문을 취소합니다. 조리를 시작한 뒤에는 409 `order-status-conflict`.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "취소 완료"),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "이 가게의 주문이 아닌 경우(order-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "접수 대기가 아닌 경우(order-status-conflict)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@Parameter(description = "가게 ID", example = "10") @PathVariable Long storeId,
                                @Parameter(description = "주문 ID", example = "1024") @PathVariable Long orderId,
                                @AuthenticationPrincipal AuthMember authMember) {
        return orderService.cancelByStore(storeId, orderId, authMember);
    }
}
