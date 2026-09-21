package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.service.StoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가게 수정 API의 HTTP 계약 확인.
 *
 * <p>프론트가 이 스펙으로 타입을 생성해 화면을 만들기 때문에, 경로와 요청 검증 규칙이
 * 백엔드 사정으로 조용히 바뀌지 않도록 여기서 고정한다.
 */
@WebMvcTest(StoreController.class)
class StoreControllerTest {

    private static final String STORE_PATH = "/api/stores/1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    /**
     * 구현 이후에도 유지되는 요청 검증 계약.
     *
     * <p>검증을 통과하는 케이스는 지금 501 을 받는다. 구현 PR에서는 <b>어서션만</b> 200 으로 바꾸고
     * 케이스 자체는 남겨야 한다 — 통째로 지우면 "이 입력은 유효하다"는 계약이 테스트에서 사라진다.
     */
    @Nested
    @DisplayName("요청 검증 — 구현 이후에도 유지되는 계약")
    class RequestValidation {

        @Test
        @DisplayName("가게명이 200자를 넘으면 400을 반환한다")
        void rejectsTooLongName() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "가".repeat(201) + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가게명이 정확히 200자면 통과한다 — 경계 바로 안쪽")
        void acceptsNameAtMaxLength() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "가".repeat(200) + "\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("가게명이 한 글자여도 통과한다 — 최소 경계")
        void acceptsSingleCharacterName() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"곰\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("가게명이 빈 문자열이면 400을 반환한다 — 이름은 지울 수 없는 값이다")
        void rejectsBlankName() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가게명이 공백뿐이면 400을 반환한다 — @Size 만으로는 통과하던 구멍")
        void rejectsWhitespaceOnlyName() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("도로명 주소가 500자를 넘으면 400을 반환한다")
        void rejectsTooLongAddress() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"" + "가".repeat(501) + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("도로명 주소가 정확히 500자면 통과한다 — 경계 바로 안쪽")
        void acceptsAddressAtMaxLength() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"" + "가".repeat(500) + "\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("도로명 주소가 공백뿐이면 400을 반환한다")
        void rejectsWhitespaceOnlyAddress() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"  \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("전화번호가 20자를 넘으면 400을 반환한다")
        void rejectsTooLongPhone() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"012345678901234567890\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("전화번호가 정확히 20자면 통과한다 — 경계 바로 안쪽")
        void acceptsPhoneAtMaxLength() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"01234567890123456789\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("전화번호는 빈 문자열로 지울 수 있다 — 이름·주소와 다르다")
        void acceptsEmptyPhone() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("status 에 StoreStatus 에 없는 값이 오면 400을 반환한다")
        void rejectsUnknownStatusValue() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"영업중\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("모든 필드가 선택이므로 빈 본문도 검증을 통과한다")
        void acceptsEmptyBody() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("storeId 가 숫자가 아니면 ErrorResponse 규격으로 400을 반환한다")
        void rejectsNonNumericStoreId() throws Exception {
            // 이 경로만 GlobalExceptionHandler 를 타서 {code, message} 로 응답한다.
            // 반면 위의 본문 검증 실패들은 아직 스프링 기본 형태로 나간다 — 에러 규격화 티켓에서 맞춘다.
            mockMvc.perform(patch("/api/stores/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"맛나 치킨\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    @Nested
    @DisplayName("스펙 선공개 단계 — 구현 PR에서 삭제되는 케이스")
    class StubBehavior {

        @Test
        @DisplayName("PATCH /api/stores/{storeId} 가 라우팅되고 아직 501을 반환한다")
        void patchIsRoutedAndNotImplemented() throws Exception {
            mockMvc.perform(patch(STORE_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"맛나 치킨\",\"status\":\"OPEN\"}"))
                    .andExpect(status().isNotImplemented());
        }

        @Test
        @DisplayName("POST /api/stores/{storeId}/confirm 이 라우팅되고 아직 501을 반환한다")
        void confirmIsRoutedAndNotImplemented() throws Exception {
            mockMvc.perform(post(STORE_PATH + "/confirm"))
                    .andExpect(status().isNotImplemented());
        }
    }
}
