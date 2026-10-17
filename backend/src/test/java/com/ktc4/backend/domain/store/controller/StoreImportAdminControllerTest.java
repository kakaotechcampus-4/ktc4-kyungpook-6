package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.StoreImportRequest;
import com.ktc4.backend.domain.store.dto.StoreImportResponse;
import com.ktc4.backend.domain.store.service.StoreImportService;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가게 자료 주입 API 의 HTTP 계약 확인.
 *
 * <p>이 API 로 배포 환경에 자료를 넣게 되므로, <b>관리자만 부를 수 있다</b>는 것과
 * <b>요청 형식이 틀리면 아무것도 넣지 않는다</b>는 두 가지를 여기서 고정한다.
 */
@WebMvcTest(StoreImportAdminController.class)
@Import(SecurityConfig.class)
class StoreImportAdminControllerTest {

    private static final String IMPORT_PATH = "/api/admin/stores/imports";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreImportService storeImportService;

    private static String body(String stores) {
        return "{\"stores\":[" + stores + "]}";
    }

    private static String store(String name) {
        return "{\"name\":\"" + name + "\",\"addressRoad\":\"가상특별시 예시구 샘플로 1\"}";
    }

    @Nested
    @DisplayName("관리자만 부를 수 있다")
    @WithAnonymousUser
    class Authorization {

        @Test
        @DisplayName("로그인하지 않으면 401 이고 서비스는 불리지 않는다")
        void 로그인하지_않으면_401() throws Exception {
            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(store("예시분식"))))
                    .andExpect(status().isUnauthorized());

            verify(storeImportService, never()).importStores(any());
        }

        @Test
        @DisplayName("점주 토큰으로는 403 이다")
        @WithMockUser(roles = "OWNER")
        void 점주는_403() throws Exception {
            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(store("예시분식"))))
                    .andExpect(status().isForbidden());

            verify(storeImportService, never()).importStores(any());
        }
    }

    @Nested
    @DisplayName("요청 검증")
    @WithMockUser(roles = "ADMIN")
    class RequestValidation {

        @Test
        @DisplayName("정상 요청이면 서비스 결과를 그대로 내려준다")
        void 정상_요청이면_결과를_내려준다() throws Exception {
            when(storeImportService.importStores(any())).thenReturn(
                    StoreImportResponse.of(2, 1, 0,
                            List.of(new StoreImportResponse.Rejection(1, "!!!", "가게명을 정규화하면 남는 글자가 없습니다"))));

            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(store("예시분식") + "," + store("!!!"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requested").value(2))
                    .andExpect(jsonPath("$.inserted").value(1))
                    .andExpect(jsonPath("$.skipped").value(0))
                    .andExpect(jsonPath("$.rejected").value(1))
                    .andExpect(jsonPath("$.rejections[0].index").value(1));
        }

        @Test
        @DisplayName("가게 목록이 비면 400 이고 아무것도 넣지 않는다")
        void 목록이_비면_400() throws Exception {
            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"stores\":[]}"))
                    .andExpect(status().isBadRequest());

            verify(storeImportService, never()).importStores(any());
        }

        @Test
        @DisplayName("가게명이 비면 400 — 한 줄이라도 틀리면 요청 전체가 돌아간다")
        void 가게명이_비면_400() throws Exception {
            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(store("예시분식") + ","
                                    + "{\"name\":\"  \",\"addressRoad\":\"가상특별시 예시구 샘플로 2\"}")))
                    .andExpect(status().isBadRequest());

            verify(storeImportService, never()).importStores(any());
        }

        @Test
        @DisplayName("위도가 범위를 벗어나면 400")
        void 위도가_범위를_벗어나면_400() throws Exception {
            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("{\"name\":\"예시분식\",\"addressRoad\":\"가상특별시 예시구 샘플로 1\","
                                    + "\"lat\":91.0}")))
                    .andExpect(status().isBadRequest());

            verify(storeImportService, never()).importStores(any());
        }

        @Test
        @DisplayName("한 번에 보낼 수 있는 건수를 넘기면 400")
        void 건수_상한을_넘기면_400() throws Exception {
            String tooMany = IntStream.rangeClosed(0, StoreImportRequest.MAX_STORES)
                    .mapToObj(index -> store("예시분식" + index))
                    .collect(Collectors.joining(","));

            mockMvc.perform(post(IMPORT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(tooMany)))
                    .andExpect(status().isBadRequest());

            verify(storeImportService, never()).importStores(any());
        }
    }
}
