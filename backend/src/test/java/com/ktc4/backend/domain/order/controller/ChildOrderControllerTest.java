package com.ktc4.backend.domain.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.order.dto.ChildOrderCreateRequest;
import com.ktc4.backend.domain.order.dto.OrderResponse;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아동 주문 API 의 HTTP 계약. 아동 토큰은 이 컨텍스트의 실제 {@link JwtProvider} 로 발급한다 — 토큰의 아동 번호가
 * 서비스로 가는지 보려고. 주문 로직은 {@code OrderServiceTest} 몫이다.
 */
@WebMvcTest(ChildOrderController.class)
@Import(SecurityConfig.class)
@DisplayName("ChildOrderController")
class ChildOrderControllerTest {

    static final OrderResponse RECEIVED = new OrderResponse(1024L, 10L, "예시분식", "후라이드 치킨", ReceiveType.TAKEOUT,
            OrderStatus.RECEIVED, LocalDateTime.of(2026, 11, 4, 12, 40), null, null, null);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    private String child(long childId) {
        return "Bearer " + jwtProvider.issueChild(childId).value();
    }

    @Test
    @DisplayName("주문하면 201 이고, 응답 칸은 주문 칸 그대로다 — 아동 번호는 없다")
    void creates() throws Exception {
        when(orderService.create(7L, new ChildOrderCreateRequest(10L, 3L, ReceiveType.TAKEOUT))).thenReturn(RECEIVED);

        String body = mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":10,\"menuId\":3,\"receiveType\":\"TAKEOUT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(1024))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.orderedAt").value("2026-11-04T12:40:00"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("orderId", "storeId", "storeName", "menuName",
                "receiveType", "status", "orderedAt", "cookingStartedAt", "completedAt", "canceledAt");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"menuId\":3,\"receiveType\":\"TAKEOUT\"}", "{\"storeId\":10,\"receiveType\":\"TAKEOUT\"}",
            "{\"storeId\":10,\"menuId\":3}", "{\"storeId\":0,\"menuId\":3,\"receiveType\":\"TAKEOUT\"}",
            "{\"storeId\":10,\"menuId\":-1,\"receiveType\":\"TAKEOUT\"}"})
    @DisplayName("가게·메뉴·받는 방법이 빠졌거나 번호가 양수가 아니면 서비스를 부르지 않고 400 invalid-request")
    void rejectsInvalidBody(String body) throws Exception {
        mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child(7L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));

        verify(orderService, never()).create(anyLong(), any());
    }

    @Test
    @DisplayName("모르는 받는 방법은 400 invalid-request")
    void rejectsUnknownReceiveType() throws Exception {
        mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":10,\"menuId\":3,\"receiveType\":\"DELIVERY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_REQUEST.getType().toString()));
    }

    @Test
    @DisplayName("진행 중 주문이 있으면 409 active-order-exists 가 그대로 나간다")
    void activeOrderExists() throws Exception {
        when(orderService.create(anyLong(), any())).thenThrow(new CustomException(ErrorCode.ACTIVE_ORDER_EXISTS));

        mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":10,\"menuId\":3,\"receiveType\":\"TAKEOUT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.ACTIVE_ORDER_EXISTS.getType().toString()));
    }

    @Test
    @DisplayName("진행 중 주문은 {order: 주문} 이다")
    void activeOrder() throws Exception {
        when(orderService.findActive(7L)).thenReturn(Optional.of(RECEIVED));

        mockMvc.perform(get("/api/children/me/orders/active").header(HttpHeaders.AUTHORIZATION, child(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderId").value(1024));
    }

    @Test
    @DisplayName("진행 중 주문이 없으면 200 {order: null} — 빈 본문(204)이 아니다")
    void noActiveOrder() throws Exception {
        when(orderService.findActive(7L)).thenReturn(Optional.empty());

        String body = mockMvc.perform(get("/api/children/me/orders/active").header(HttpHeaders.AUTHORIZATION, child(7L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.has("order")).isTrue();
        assertThat(json.get("order").isNull()).isTrue();
    }

    @Test
    @DisplayName("취소는 토큰의 아동 번호와 경로의 주문 번호로 부른다")
    void cancels() throws Exception {
        when(orderService.cancelByChild(7L, 1024L)).thenReturn(RECEIVED);

        mockMvc.perform(post("/api/children/me/orders/1024/cancel").header(HttpHeaders.AUTHORIZATION, child(7L)))
                .andExpect(status().isOk());

        verify(orderService).cancelByChild(7L, 1024L);
    }
}
