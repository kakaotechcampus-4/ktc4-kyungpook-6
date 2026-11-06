package com.ktc4.backend.domain.order.controller;

import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.order.service.OrderService;
import com.ktc4.backend.global.dto.PageResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 주문 내역 API 의 HTTP 계약 — 다른 관리자 목록과 같은 페이지 규칙(page 0~, limit 1~100)과 응답 래퍼.
 */
@WebMvcTest(OrderAdminController.class)
@Import(SecurityConfig.class)
@DisplayName("OrderAdminController")
class OrderAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    private String admin() {
        return "Bearer " + jwtProvider.issue(1L, MemberRole.ADMIN).value();
    }

    @Test
    @DisplayName("기본 page 0·limit 20 으로 부르고, 페이지 래퍼로 내려 준다")
    void lists() throws Exception {
        when(orderService.listAll(0, 20)).thenReturn(
                new PageResponse<>(List.of(ChildOrderControllerTest.RECEIVED), 0, 20, 1, 1, false));

        mockMvc.perform(get("/api/admin/orders").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].storeName").value("예시분식"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"page=-1", "limit=0", "limit=101"})
    @DisplayName("page 가 음수거나 limit 이 1~100 밖이면 400 이고 서비스를 부르지 않는다")
    void rejectsBadPaging(String query) throws Exception {
        mockMvc.perform(get("/api/admin/orders?" + query).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).listAll(anyInt(), anyInt());
    }
}
