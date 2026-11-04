package com.ktc4.backend.domain.order.controller;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 주문 API 의 HTTP 계약. 어느 가게의 어느 주문을 누가 다루는지가 서비스로 그대로 가는지 본다.
 */
@WebMvcTest(StoreOrderController.class)
@Import(SecurityConfig.class)
@DisplayName("StoreOrderController")
class StoreOrderControllerTest {

    private static final AuthMember OWNER = new AuthMember(5L, MemberRole.OWNER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    private String owner() {
        return "Bearer " + jwtProvider.issue(5L, MemberRole.OWNER).value();
    }

    @Test
    @DisplayName("오늘 주문 목록은 배열이다")
    void lists() throws Exception {
        when(orderService.listForStore(10L, OWNER)).thenReturn(List.of(ChildOrderControllerTest.RECEIVED));

        mockMvc.perform(get("/api/stores/10/orders").header(HttpHeaders.AUTHORIZATION, owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(1024))
                .andExpect(jsonPath("$[0].menuName").value("후라이드 치킨"));
    }

    @Test
    @DisplayName("조리 시작·완료·취소는 경로의 가게·주문 번호와 로그인한 사람으로 부른다")
    void actions() throws Exception {
        when(orderService.startCooking(10L, 1024L, OWNER)).thenReturn(ChildOrderControllerTest.RECEIVED);
        when(orderService.complete(10L, 1024L, OWNER)).thenReturn(ChildOrderControllerTest.RECEIVED);
        when(orderService.cancelByStore(10L, 1024L, OWNER)).thenReturn(ChildOrderControllerTest.RECEIVED);

        for (String action : List.of("start-cooking", "complete", "cancel")) {
            mockMvc.perform(post("/api/stores/10/orders/1024/" + action).header(HttpHeaders.AUTHORIZATION, owner()))
                    .andExpect(status().isOk());
        }

        verify(orderService).startCooking(10L, 1024L, OWNER);
        verify(orderService).complete(10L, 1024L, OWNER);
        verify(orderService).cancelByStore(10L, 1024L, OWNER);
    }

    @Test
    @DisplayName("지금 상태에서 할 수 없으면 409 order-status-conflict")
    void statusConflict() throws Exception {
        when(orderService.complete(eq(10L), eq(1024L), any())).thenThrow(new CustomException(ErrorCode.ORDER_STATUS_CONFLICT));

        mockMvc.perform(post("/api/stores/10/orders/1024/complete").header(HttpHeaders.AUTHORIZATION, owner()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.ORDER_STATUS_CONFLICT.getType().toString()));
    }
}
