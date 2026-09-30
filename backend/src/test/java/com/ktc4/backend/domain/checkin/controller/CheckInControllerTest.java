package com.ktc4.backend.domain.checkin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.service.CheckInService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 체크인 API 의 HTTP 계약 확인.
 *
 * <p>점주 앱이 이 응답을 그대로 화면에 쓰므로, 응답에 아동을 알아볼 수 있는 값이 끼어들지 않는지를
 * 필드 목록 전체로 고정한다. 체크인 로직 자체는 {@code CheckInServiceTest} 몫이다.
 */
@WebMvcTest(CheckInController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
@DisplayName("CheckInController")
class CheckInControllerTest {

    private static final String PATH = "/api/stores/3/check-ins";
    private static final String ERROR_BASE = "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CheckInService checkInService;

    private static String body(String qrPayload) {
        return "{\"qrPayload\":\"" + qrPayload + "\"}";
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("가게 번호와 QR 문자열을 서비스에 넘기고 201 을 반환한다")
        void createsCheckIn() throws Exception {
            when(checkInService.checkIn(3L, "v1.token"))
                    .thenReturn(new CheckInResponse(10L, LocalDateTime.of(2026, 9, 29, 12, 0)));

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v1.token")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.checkInId").value(10))
                    .andExpect(jsonPath("$.checkedInAt").value("2026-09-29T12:00:00"));

            verify(checkInService).checkIn(3L, "v1.token");
        }

        @Test
        @DisplayName("응답 필드는 checkInId, checkedInAt 두 개뿐이다 — 아동 식별 정보가 나가지 않는다")
        void exposesNoChildIdentity() throws Exception {
            when(checkInService.checkIn(anyLong(), any()))
                    .thenReturn(new CheckInResponse(10L, LocalDateTime.of(2026, 9, 29, 12, 0)));

            String json = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v1.token")))
                    .andReturn().getResponse().getContentAsString();

            JsonNode node = objectMapper.readTree(json);
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("checkInId", "checkedInAt");
        }

        @Test
        @DisplayName("QR 문자열이 정확히 100자면 통과한다 — 길이 제한 경계 안쪽")
        void acceptsPayloadAtMaxLength() throws Exception {
            when(checkInService.checkIn(anyLong(), any()))
                    .thenReturn(new CheckInResponse(10L, LocalDateTime.of(2026, 9, 29, 12, 0)));

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v".repeat(100))))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("요청 검증 — 서비스까지 가지 않는다")
    class RequestValidation {

        @Test
        @DisplayName("qrPayload 가 없으면 400 invalid-request")
        void rejectsMissingPayload() throws Exception {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "invalid-request"))
                    .andExpect(jsonPath("$.errors[0].field").value("qrPayload"));

            verify(checkInService, never()).checkIn(anyLong(), any());
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("qrPayload 가 비었거나 공백뿐이면 400 invalid-request")
        void rejectsBlankPayload(String payload) throws Exception {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "invalid-request"));

            verify(checkInService, never()).checkIn(anyLong(), any());
        }

        @Test
        @DisplayName("qrPayload 가 100자를 넘으면 400 invalid-request")
        void rejectsTooLongPayload() throws Exception {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v".repeat(101))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "invalid-request"));

            verify(checkInService, never()).checkIn(anyLong(), any());
        }

        @Test
        @DisplayName("가게 번호가 숫자가 아니면 400 invalid-request")
        void rejectsNonNumericStoreId() throws Exception {
            mockMvc.perform(post("/api/stores/abc/check-ins")
                            .contentType(MediaType.APPLICATION_JSON).content(body("v1.token")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "invalid-request"));

            verify(checkInService, never()).checkIn(anyLong(), any());
        }

        @Test
        @DisplayName("본문 검증 실패와 함께 가게 번호가 0 이어도 필드별 errors 가 유지된다 — 경로 변수 제약이 errors 를 지우던 회귀 고정")
        void keepsFieldErrorsRegardlessOfStoreId() throws Exception {
            mockMvc.perform(post("/api/stores/0/check-ins").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("qrPayload"));
        }
    }

    @Nested
    @DisplayName("서비스 에러의 응답 규격")
    class ServiceErrors {

        @Test
        @DisplayName("QR 이 맞지 않으면 400 invalid-qr-token")
        void mapsInvalidQrToken() throws Exception {
            when(checkInService.checkIn(eq(3L), any())).thenThrow(new CustomException(ErrorCode.INVALID_QR_TOKEN));

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v1.wrong")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "invalid-qr-token"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value(ErrorCode.INVALID_QR_TOKEN.getTitle()));
        }

        @Test
        @DisplayName("가게가 없으면 404 store-not-found")
        void mapsStoreNotFound() throws Exception {
            when(checkInService.checkIn(eq(3L), any())).thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("v1.token")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(ERROR_BASE + "store-not-found"))
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}
