package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.StoreDeleteResponse;
import com.ktc4.backend.domain.store.dto.StoreDeletionRequest;
import com.ktc4.backend.domain.store.dto.StoreDeletionResponse;
import com.ktc4.backend.domain.store.service.StoreDeletionService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가게 삭제 API 의 HTTP 계약 확인.
 *
 * <p>되돌릴 수 없는 일이므로 <b>관리자만 부를 수 있다</b>는 것과, <b>한 번에 지울 수 있는 양에
 * 상한이 있다</b>는 것을 여기서 고정한다. 거절(409)이 어떤 모양으로 나가는지도 함께 본다.
 */
@WebMvcTest(StoreDeleteAdminController.class)
@Import(SecurityConfig.class)
class StoreDeleteAdminControllerTest {

    private static final String STORE_PATH = "/api/admin/stores/12";
    private static final String DELETIONS_PATH = "/api/admin/stores/deletions";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreDeletionService storeDeletionService;

    private static String body(String ids) {
        return "{\"storeIds\":[" + ids + "]}";
    }

    @Nested
    @DisplayName("관리자만 부를 수 있다")
    @WithAnonymousUser
    class Authorization {

        @Test
        @DisplayName("로그인하지 않으면 단건 삭제가 401 이고 서비스는 불리지 않는다")
        void 로그인하지_않으면_401() throws Exception {
            mockMvc.perform(delete(STORE_PATH))
                    .andExpect(status().isUnauthorized());

            verify(storeDeletionService, never()).delete(any());
        }

        @Test
        @DisplayName("점주 토큰으로는 403 이고 서비스는 불리지 않는다")
        @WithMockUser(roles = "OWNER")
        void 점주는_403() throws Exception {
            mockMvc.perform(delete(STORE_PATH))
                    .andExpect(status().isForbidden());

            verify(storeDeletionService, never()).delete(any());
        }

        @Test
        @DisplayName("로그인하지 않으면 여러 건 삭제도 401 이다")
        void 여러건도_401() throws Exception {
            mockMvc.perform(post(DELETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("1,2")))
                    .andExpect(status().isUnauthorized());

            verify(storeDeletionService, never()).deleteAll(any());
        }
    }

    @Nested
    @DisplayName("관리자 토큰으로 부르면")
    @WithMockUser(roles = "ADMIN")
    class AsAdmin {

        @Test
        @DisplayName("단건 삭제는 함께 지운 줄 수를 돌려준다")
        void 단건_삭제() throws Exception {
            when(storeDeletionService.delete(12L))
                    .thenReturn(new StoreDeleteResponse(12L, "가나다분식", 1, 2, 0, 0, 3, 1, 4));

            mockMvc.perform(delete(STORE_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(12))
                    .andExpect(jsonPath("$.name").value("가나다분식"))
                    .andExpect(jsonPath("$.tasks").value(3))
                    .andExpect(jsonPath("$.signals").value(4));
        }

        @Test
        @DisplayName("없는 가게는 404 이고 problem+json 으로 나간다")
        void 없는_가게는_404() throws Exception {
            when(storeDeletionService.delete(12L)).thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

            mockMvc.perform(delete(STORE_PATH))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value(ErrorCode.STORE_NOT_FOUND.getTitle()));
        }

        @Test
        @DisplayName("점주가 연결된 가게는 409 다")
        void 점주_연결은_409() throws Exception {
            when(storeDeletionService.delete(12L)).thenThrow(new CustomException(ErrorCode.STORE_HAS_OWNER));

            mockMvc.perform(delete(STORE_PATH))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value(ErrorCode.STORE_HAS_OWNER.getTitle()));
        }

        @Test
        @DisplayName("여러 건 삭제는 지운 건수와 거절 내역을 돌려준다")
        void 여러건_삭제() throws Exception {
            when(storeDeletionService.deleteAll(any())).thenReturn(StoreDeletionResponse.of(
                    3, 1,
                    List.of(new StoreDeleteResponse(1L, "구름빵집", 1, 0, 0, 0, 0, 0, 0)),
                    List.of(new StoreDeletionResponse.Refusal(2L, "해오름식당",
                            ErrorCode.STORE_UNDER_INVESTIGATION.getTitle()))));

            mockMvc.perform(post(DELETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("1,2,999")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requested").value(3))
                    .andExpect(jsonPath("$.deleted").value(1))
                    .andExpect(jsonPath("$.notFound").value(1))
                    .andExpect(jsonPath("$.refused").value(1))
                    .andExpect(jsonPath("$.refusals[0].name").value("해오름식당"));
        }

        @Test
        @DisplayName("ID 목록이 비면 400 이고 서비스는 불리지 않는다")
        void 빈_목록은_400() throws Exception {
            mockMvc.perform(post(DELETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("")))
                    .andExpect(status().isBadRequest());

            verify(storeDeletionService, never()).deleteAll(any());
        }

        @Test
        @DisplayName("상한을 넘기면 400 이고 아무것도 지우지 않는다")
        void 상한_초과는_400() throws Exception {
            String tooMany = IntStream.rangeClosed(1, StoreDeletionRequest.MAX_STORES + 1)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(","));

            mockMvc.perform(post(DELETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(tooMany)))
                    .andExpect(status().isBadRequest());

            verify(storeDeletionService, never()).deleteAll(any());
        }

        @Test
        @DisplayName("상한만큼은 받는다 — 경계에서 막히지 않는다")
        void 상한_경계는_통과() throws Exception {
            String justEnough = IntStream.rangeClosed(1, StoreDeletionRequest.MAX_STORES)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining(","));
            when(storeDeletionService.deleteAll(any()))
                    .thenReturn(StoreDeletionResponse.of(StoreDeletionRequest.MAX_STORES,
                            StoreDeletionRequest.MAX_STORES, List.of(), List.of()));

            mockMvc.perform(post(DELETIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(justEnough)))
                    .andExpect(status().isOk());

            verify(storeDeletionService).deleteAll(any());
        }

        @Test
        @DisplayName("경로의 가게 ID 를 그대로 서비스에 넘긴다")
        void 경로_ID를_넘긴다() throws Exception {
            when(storeDeletionService.delete(eq(12L)))
                    .thenReturn(new StoreDeleteResponse(12L, "달빛국수", 0, 0, 0, 0, 0, 0, 0));

            mockMvc.perform(delete(STORE_PATH)).andExpect(status().isOk());

            verify(storeDeletionService).delete(12L);
        }
    }
}
