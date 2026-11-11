package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.service.NearbyStoreService;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주변 가게 API 의 경로·권한·파라미터 검증. 찾는 로직은 {@code NearbyStoreServiceTest}(Postgres)가 본다.
 */
@WebMvcTest(NearbyStoreController.class)
@Import(SecurityConfig.class)
@DisplayName("NearbyStoreController")
class NearbyStoreControllerTest {

    private static final String PATH = "/api/stores/nearby";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NearbyStoreService nearbyStoreService;

    @Test
    @WithMockUser(roles = "CHILD")
    @DisplayName("아동은 부를 수 있고, 반경을 주지 않으면 2000m 로 찾는다")
    void childGetsNearbyWithDefaultRadius() throws Exception {
        when(nearbyStoreService.findNearby(anyDouble(), anyDouble(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get(PATH).param("lat", "35.8884").param("lng", "128.6107"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(nearbyStoreService).findNearby(35.8884, 128.6107, 2000, null);
    }

    @Test
    @WithMockUser(roles = "CHILD")
    @DisplayName("반경·검색어를 그대로 넘긴다")
    void passesRadiusAndQuery() throws Exception {
        when(nearbyStoreService.findNearby(anyDouble(), anyDouble(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get(PATH).param("lat", "35.8884").param("lng", "128.6107")
                        .param("radius", "1000").param("q", "떡볶이"))
                .andExpect(status().isOk());

        verify(nearbyStoreService).findNearby(35.8884, 128.6107, 1000, "떡볶이");
    }

    @Test
    @WithMockUser(roles = "CHILD")
    @DisplayName("위경도가 없거나 범위 밖(NaN·Infinity 포함), 반경 100~100000 밖, 검색어 50자 초과면 400 이고 찾지 않는다")
    void rejectsBadParameters() throws Exception {
        String longQuery = "가".repeat(51);
        List<String[]> bad = List.of(
                new String[]{"lng", "128.6107"},
                new String[]{"lat", "35.8884"},
                new String[]{"lat", "91", "lng", "128.6107"},
                new String[]{"lat", "35.8884", "lng", "-181"},
                // double 파싱은 "NaN"·"Infinity" 를 받아 준다 — 범위 검증이 막아야 한다
                new String[]{"lat", "NaN", "lng", "128.6107"},
                new String[]{"lat", "35.8884", "lng", "NaN"},
                new String[]{"lat", "Infinity", "lng", "128.6107"},
                new String[]{"lat", "35.8884", "lng", "-Infinity"},
                new String[]{"lat", "35.8884", "lng", "128.6107", "radius", "99"},
                new String[]{"lat", "35.8884", "lng", "128.6107", "radius", "100001"},
                new String[]{"lat", "35.8884", "lng", "128.6107", "q", longQuery},
                new String[]{"lat", "abc", "lng", "128.6107"});
        for (String[] params : bad) {
            var request = get(PATH);
            for (int i = 0; i < params.length; i += 2) {
                request = request.param(params[i], params[i + 1]);
            }
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }
        verify(nearbyStoreService, never()).findNearby(anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH).param("lat", "35.8884").param("lng", "128.6107"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"OWNER", "ADMIN"})
    @DisplayName("점주·관리자 토큰은 403 — 아동 앱 전용이다")
    void ownerAndAdminAreForbidden() throws Exception {
        mockMvc.perform(get(PATH).param("lat", "35.8884").param("lng", "128.6107"))
                .andExpect(status().isForbidden());
        verify(nearbyStoreService, never()).findNearby(anyDouble(), anyDouble(), anyInt(), isNull());
    }
}
