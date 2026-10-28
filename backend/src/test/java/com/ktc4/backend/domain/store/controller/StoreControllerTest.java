package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.store.dto.BusinessHourRequest;
import com.ktc4.backend.domain.store.dto.BusinessHourResponse;
import com.ktc4.backend.domain.store.dto.MenuRequest;
import com.ktc4.backend.domain.store.dto.MenuResponse;
import com.ktc4.backend.domain.store.dto.OperatingInfoRequest;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreDetailResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.enums.BusinessDay;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreManagementService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.security.SecurityConfig;
import com.ktc4.backend.support.BusinessHourFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가게 수정 API의 HTTP 계약 확인.
 *
 * <p>프론트가 이 스펙으로 타입을 생성해 화면을 만들기 때문에, 경로와 요청 검증 규칙이
 * 백엔드 사정으로 조용히 바뀌지 않도록 여기서 고정한다.
 */
@WebMvcTest(StoreController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class StoreControllerTest {

    private static final String STORE_PATH = "/api/stores/1";
    private static final long ADMIN_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private StoreManagementService storeManagementService;

    // 가게 수정은 로그인한 관리자를 변경 기록의 "누가"로 넘긴다 — 클래스의 @WithMockUser 는 AuthMember 가 아니라서
    // 수정 요청에는 실제 로그인 정보를 싣는다
    private static RequestPostProcessor adminLogin() {
        AuthMember admin = new AuthMember(ADMIN_ID, MemberRole.ADMIN);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.authorities()));
    }

    /**
     * 서비스가 반환했다고 가정하는 표본 응답. 이 계층은 서비스를 Mock 으로 대체하므로,
     * 검증 통과 케이스에서 실제로 확인하는 건 "컨트롤러가 서비스 호출까지 연결되고, 그 반환값을
     * 그대로 직렬화해 내려주는지"이지 서비스 내부 로직이 아니다 — 그건 {@code StoreServiceTest} 몫이다.
     */
    private static StoreResponse sampleResponse() {
        return new StoreResponse(1L, "맛나 치킨", "대구광역시 북구 대학로 80",
                12.3456, 123.4567, StoreStatus.OPEN, "치킨",
                "010-1234-5678", "1234567890", LocalDateTime.of(2026, 9, 23, 10, 0));
    }

    /**
     * 요청 검증 계약. 검증을 통과하는 케이스는 서비스 호출까지 이어져 200을 반환한다.
     */
    @Nested
    @DisplayName("요청 검증")
    class RequestValidation {

        @Test
        @DisplayName("가게명이 200자를 넘으면 400을 반환한다")
        void rejectsTooLongName() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "가".repeat(201) + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가게명이 정확히 200자면 통과한다 — 경계 바로 안쪽")
        void acceptsNameAtMaxLength() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "가".repeat(200) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("가게명이 한 글자여도 통과한다 — 최소 경계")
        void acceptsSingleCharacterName() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"곰\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("가게명이 빈 문자열이면 400을 반환한다 — 이름은 지울 수 없는 값이다")
        void rejectsBlankName() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("가게명이 공백뿐이면 400을 반환한다 — @Size 만으로는 통과하던 구멍")
        void rejectsWhitespaceOnlyName() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("도로명 주소가 500자를 넘으면 400을 반환한다")
        void rejectsTooLongAddress() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"" + "가".repeat(501) + "\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("도로명 주소가 정확히 500자면 통과한다 — 경계 바로 안쪽")
        void acceptsAddressAtMaxLength() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"" + "가".repeat(500) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("도로명 주소가 공백뿐이면 400을 반환한다")
        void rejectsWhitespaceOnlyAddress() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressRoad\":\"  \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("전화번호가 20자를 넘으면 400을 반환한다")
        void rejectsTooLongPhone() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"012345678901234567890\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("전화번호가 정확히 20자면 통과한다 — 경계 바로 안쪽")
        void acceptsPhoneAtMaxLength() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"01234567890123456789\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("전화번호는 빈 문자열로 지울 수 있다 — 이름·주소와 다르다")
        void acceptsEmptyPhone() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("status 에 StoreStatus 에 없는 값이 오면 400을 반환한다")
        void rejectsUnknownStatusValue() throws Exception {
            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"영업중\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("모든 필드가 선택이므로 빈 본문도 검증을 통과한다")
        void acceptsEmptyBody() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1));
        }

        @Test
        @DisplayName("storeId 가 숫자가 아니면 표준 에러 규격으로 400을 반환한다")
        void rejectsNonNumericStoreId() throws Exception {
            // 에러 응답 규격(type 이 식별자)은 GlobalExceptionHandlerTest 가 전담한다. 여기서는
            // 이 경로가 규격을 타는지만 확인한다.
            mockMvc.perform(patch("/api/stores/abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"맛나 치킨\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/invalid-request"))
                    .andExpect(jsonPath("$.title").value("요청 값이 올바르지 않습니다"));
        }
    }

    /**
     * 가게 정보 수정·확인 API 의 정상 동작 계약.
     */
    @Nested
    @DisplayName("가게 정보 수정·확인")
    class UpdateAndConfirm {

        @Test
        @DisplayName("PATCH /api/stores/{storeId} 는 요청 본문을 그대로 서비스에 넘기고, 서비스가 반환한 가게 정보를 그대로 내려준다")
        void updateStoreReturnsServiceResult() throws Exception {
            when(storeService.updateStore(eq(1L), any(), any())).thenReturn(sampleResponse());

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"맛나 치킨\",\"status\":\"OPEN\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1))
                    .andExpect(jsonPath("$.name").value("맛나 치킨"))
                    .andExpect(jsonPath("$.status").value("OPEN"));

            // 응답값만 확인하면 컨트롤러가 요청 본문을 누락·훼손해 넘겨도 못 잡는다 —
            // 실제로 파싱된 요청이 서비스까지 그대로 전달됐는지 인자 자체를 확인한다.
            // 누가 바꿨는지는 로그인한 관리자에서 꺼내 넘긴다 — 엉뚱한 값을 넘기면 변경 기록의 "누가"가 틀린다
            verify(storeService).updateStore(eq(1L),
                    eq(StoreUpdateRequest.ofBasic("맛나 치킨", null, null, StoreStatus.OPEN)),
                    argThat(change -> change.source() == StoreChangeSource.STORE_EDIT
                            && change.changedBy() == ADMIN_ID && change.taskId() == null));
        }

        @Test
        @DisplayName("POST /api/stores/{storeId}/confirm 은 서비스가 반환한 가게 정보를 그대로 내려준다")
        void confirmStoreReturnsServiceResult() throws Exception {
            when(storeService.confirmStore(eq(1L))).thenReturn(sampleResponse());

            mockMvc.perform(post(STORE_PATH + "/confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1))
                    .andExpect(jsonPath("$.lastCheckedAt").value("2026-09-23T10:00:00"));
        }

        @Test
        @DisplayName("PATCH 대상 가게가 없으면 STORE_NOT_FOUND 로 404를 반환한다")
        void updateStoreReturns404WhenNotFound() throws Exception {
            when(storeService.updateStore(anyLong(), any(), any()))
                    .thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

            mockMvc.perform(patch(STORE_PATH).with(adminLogin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/store-not-found"))
                    .andExpect(jsonPath("$.title").value("요청한 가게를 찾을 수 없습니다"));
        }

        @Test
        @DisplayName("confirm 대상 가게가 없으면 STORE_NOT_FOUND 로 404를 반환한다")
        void confirmStoreReturns404WhenNotFound() throws Exception {
            when(storeService.confirmStore(anyLong()))
                    .thenThrow(new CustomException(ErrorCode.STORE_NOT_FOUND));

            mockMvc.perform(post(STORE_PATH + "/confirm"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/store-not-found"))
                    .andExpect(jsonPath("$.title").value("요청한 가게를 찾을 수 없습니다"));
        }
    }

    /**
     * 국세청 대조 자료 조회 API 의 HTTP 계약 확인.
     *
     * <p>AI 쪽에서 이 응답을 그대로 읽어 조사 대상을 고르므로, 필드 이름과 필터 값이
     * 백엔드 사정으로 조용히 바뀌지 않도록 여기서 고정한다.
     */
    @Nested
    @DisplayName("국세청 대조 자료 조회")
    class NtsChecks {

        private static final String NTS_CHECK_PATH = "/api/stores/nts-checks";

        private PageResponse<StoreCheckResponse> onePage() {
            StoreCheckResponse response = new StoreCheckResponse(
                    1L, "예시분식", "예시분식",
                    "가상특별시 예시구 샘플로 123", "가상특별시예시구샘플로123",
                    12.3456, 123.4567, "000-1234-5678", "1234567890",
                    StoreStatus.OPEN, NtsLookupResult.CONFIRMED, BusinessState.CLOSED,
                    LocalDate.of(2026, 3, 1), StatusComparison.OPEN_BUT_CLOSED, true, false,
                    LocalDateTime.of(2026, 9, 22, 3, 0));
            return new PageResponse<>(List.of(response), 0, 20, 1, 1, false);
        }

        @Test
        @DisplayName("가게 정보와 국세청 확인 결과를 함께 반환한다")
        void returnsStoreWithNtsCheck() throws Exception {
            when(storeService.getNtsChecks(isNull(), anyInt(), anyInt())).thenReturn(onePage());

            mockMvc.perform(get(NTS_CHECK_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].storeId").value(1))
                    .andExpect(jsonPath("$.content[0].nameNormalized").value("예시분식"))
                    .andExpect(jsonPath("$.content[0].internalStatus").value("OPEN"))
                    .andExpect(jsonPath("$.content[0].ntsLookup").value("CONFIRMED"))
                    .andExpect(jsonPath("$.content[0].ntsStatus").value("CLOSED"))
                    .andExpect(jsonPath("$.content[0].ntsClosedAt").value("2026-03-01"))
                    .andExpect(jsonPath("$.content[0].statusComparison").value("OPEN_BUT_CLOSED"))
                    .andExpect(jsonPath("$.content[0].statusMismatch").value(true))
                    .andExpect(jsonPath("$.content[0].dataProblem").value(false))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("필터를 주지 않으면 전체를 조회한다")
        void defaultsToNoFilter() throws Exception {
            when(storeService.getNtsChecks(isNull(), anyInt(), anyInt())).thenReturn(onePage());

            mockMvc.perform(get(NTS_CHECK_PATH)).andExpect(status().isOk());

            verify(storeService).getNtsChecks(isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("filter 값을 서비스에 그대로 넘긴다")
        void passesFilter() throws Exception {
            when(storeService.getNtsChecks(eq(NtsCheckFilter.STATUS_MISMATCH), anyInt(), anyInt()))
                    .thenReturn(onePage());

            mockMvc.perform(get(NTS_CHECK_PATH).param("filter", "STATUS_MISMATCH").param("page", "2")
                            .param("limit", "50"))
                    .andExpect(status().isOk());

            verify(storeService).getNtsChecks(NtsCheckFilter.STATUS_MISMATCH, 2, 50);
        }

        @Test
        @DisplayName("없는 filter 값이면 400을 반환한다")
        void rejectsUnknownFilter() throws Exception {
            // 에러 응답 규격(type 이 식별자, code 없음)은 GlobalExceptionHandlerTest 가 전담한다.
            // 여기서는 이 경로가 400 으로 막히는지만 확인한다.
            mockMvc.perform(get(NTS_CHECK_PATH).param("filter", "SOMETHING_ELSE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(
                            "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/invalid-request"));
        }

        @Test
        @DisplayName("limit 이 100을 넘으면 400을 반환한다 — 대량 조회 방지")
        void rejectsTooLargeLimit() throws Exception {
            mockMvc.perform(get(NTS_CHECK_PATH).param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("page 가 음수면 400을 반환한다")
        void rejectsNegativePage() throws Exception {
            mockMvc.perform(get(NTS_CHECK_PATH).param("page", "-1"))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * 가게 상세 조회·운영 정보 저장의 계약. 지금은 인터페이스만 공개하고 본문 없이 501 을 준다.
     *
     * <p>요청 검증은 구현 뒤에도 그대로 남는 실제 동작이라 여기서 고정한다. 값 하나하나의 경계(시간 형식, 메뉴 이름)는
     * {@code BusinessHourRequestTest}·{@code MenuRequestTest} 가 맡고, 여기서는 그 검증이 이 API 에서 실제로 돌고
     * 오류가 어느 칸인지 응답에 실리는지, 개수 경계가 맞는지 본다.
     */
    @Nested
    @DisplayName("가게 상세 조회·운영 정보 저장")
    class OperatingInfo {

        private static final String OPERATING_INFO_PATH = "/api/stores/1/operating-info";
        private static final String HOUR = "{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}";
        private static final String MENU = "{\"menuId\":3,\"name\":\"김치찌개\",\"onSale\":true}";

        private static String repeat(String item, int count) {
            return "[" + String.join(",", Collections.nCopies(count, item)) + "]";
        }

        // 메뉴 ID 가 서로 다른 메뉴 count 개 — 같은 ID 를 되풀이하면 중복 규칙에 걸린다
        private static String distinctMenus(int count) {
            List<String> menus = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                menus.add("{\"menuId\":" + i + ",\"name\":\"메뉴" + i + "\",\"onSale\":true}");
            }
            return "[" + String.join(",", menus) + "]";
        }

        private static StoreDetailResponse sampleDetail() {
            return new StoreDetailResponse(1L, "예시분식", "가상특별시 예시구 샘플로 123", "053-123-4567",
                    List.of(new BusinessHourResponse(BusinessDay.MON, "11:00", "24:00"),
                            new BusinessHourResponse(BusinessDay.FRI, "21:00", "02:00")),
                    List.of(new MenuResponse(3L, "김치찌개", true, null), new MenuResponse(7L, "라면", false, null)));
        }

        // null 인 칸은 본문에서 뺀다 — "보내지 않음"을 표현한다
        private static String body(String phone, String hours, String menus) {
            List<String> fields = new ArrayList<>();
            if (phone != null) {
                fields.add("\"phone\":\"" + phone + "\"");
            }
            if (hours != null) {
                fields.add("\"businessHours\":" + hours);
            }
            if (menus != null) {
                fields.add("\"menus\":" + menus);
            }
            return "{" + String.join(",", fields) + "}";
        }

        private static String validBody() {
            return body("053-123-4567", repeat(HOUR, 1), repeat(MENU, 1));
        }

        private ResultActions save(String content) throws Exception {
            return mockMvc.perform(put(OPERATING_INFO_PATH).contentType(MediaType.APPLICATION_JSON).content(content));
        }

        @Test
        @DisplayName("가게 상세는 #96 에서 약속한 칸 이름 그대로 나간다")
        void getStoreKeepsContract() throws Exception {
            when(storeManagementService.getDetail(eq(1L), any())).thenReturn(sampleDetail());

            mockMvc.perform(get(STORE_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(1))
                    .andExpect(jsonPath("$.name").value("예시분식"))
                    .andExpect(jsonPath("$.addressRoad").value("가상특별시 예시구 샘플로 123"))
                    .andExpect(jsonPath("$.phone").value("053-123-4567"))
                    .andExpect(jsonPath("$.businessHours[0].day").value("MON"))
                    .andExpect(jsonPath("$.businessHours[0].open").value("11:00"))
                    .andExpect(jsonPath("$.businessHours[0].close").value("24:00"))
                    .andExpect(jsonPath("$.menus[1].menuId").value(7))
                    .andExpect(jsonPath("$.menus[1].name").value("라면"))
                    .andExpect(jsonPath("$.menus[1].onSale").value(false));
        }

        @Test
        @DisplayName("올바른 저장 요청은 읽은 그대로 서비스에 넘어가고, 저장된 상세를 돌려준다 — 새 메뉴는 menuId 없이")
        void validSaveReachesService() throws Exception {
            String hours = "[{\"day\":\"MON\",\"open\":\"00:00\",\"close\":\"24:00\"},"
                    + "{\"day\":\"FRI\",\"open\":\"21:00\",\"close\":\"02:00\"}]";
            String menus = "[" + MENU + ",{\"name\":\"새 메뉴\",\"onSale\":false},{\"name\":\"새 메뉴2\",\"onSale\":true}]";
            when(storeManagementService.save(eq(1L), any(), any())).thenReturn(sampleDetail());

            save(body("053-123-4567", hours, menus))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.menus[0].menuId").value(3));

            ArgumentCaptor<OperatingInfoRequest> request = ArgumentCaptor.forClass(OperatingInfoRequest.class);
            verify(storeManagementService).save(eq(1L), request.capture(), any());
            assertThat(request.getValue().phone()).isEqualTo("053-123-4567");
            assertThat(request.getValue().businessHours())
                    .extracting(BusinessHourRequest::day, BusinessHourRequest::open, BusinessHourRequest::close)
                    .containsExactly(tuple(BusinessDay.MON, "00:00", "24:00"), tuple(BusinessDay.FRI, "21:00", "02:00"));
            assertThat(request.getValue().menus())
                    .extracting(MenuRequest::menuId, MenuRequest::name, MenuRequest::onSale)
                    .containsExactly(tuple(3L, "김치찌개", true), tuple(null, "새 메뉴", false), tuple(null, "새 메뉴2", true));
        }

        @Test
        @DisplayName("같은 menuId 를 두 번 보내면 400 이고 errors 에 menus 칸이 실린다 — 서비스까지 가지 않는다")
        void rejectsDuplicateMenuIds() throws Exception {
            String menus = "[" + MENU + ",{\"name\":\"새 메뉴\",\"onSale\":true}," + MENU + "]";

            save(body("053-123-4567", repeat(HOUR, 1), menus))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", hasItem("menus")))
                    .andExpect(jsonPath("$.errors[*].message",
                            hasItem("같은 메뉴가 두 번 들어 있습니다 — 메뉴 ID 가 겹치지 않게 보내 주세요")));
            verify(storeManagementService, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("전화번호·영업시간·메뉴는 모두 보내야 한다 — 통째로 바꾸는 저장이라 빠진 칸의 뜻이 없다")
        void requiresAllFields() throws Exception {
            save(body(null, repeat(HOUR, 1), repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
            save(body("053-123-4567", null, repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("businessHours"));
            save(body("053-123-4567", repeat(HOUR, 1), null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("menus"));
        }

        @Test
        @DisplayName("전화번호는 비울 수 없고 20자까지 받는다 — 가입 때 필수인 값을 저장으로 지우지 못하게")
        void phoneRules() throws Exception {
            save(body("", repeat(HOUR, 1), repeat(MENU, 1))).andExpect(status().isBadRequest());
            save(body("   ", repeat(HOUR, 1), repeat(MENU, 1))).andExpect(status().isBadRequest());
            save(body("0".repeat(21), repeat(HOUR, 1), repeat(MENU, 1))).andExpect(status().isBadRequest());
            save(body("0".repeat(20), repeat(HOUR, 1), repeat(MENU, 1))).andExpect(status().isOk());
            save(body("053\\n123", repeat(HOUR, 1), repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }

        @Test
        @DisplayName("영업시간은 1~21줄이다")
        void businessHourCount() throws Exception {
            save(body("053-123-4567", "[]", repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("businessHours"));
            // 겹치지 않는 줄로 센다 — 같은 줄을 반복하면 개수가 아니라 겹침 때문에 거절된다
            save(body("053-123-4567", BusinessHourFixtures.nonOverlappingJson(21), repeat(MENU, 1)))
                    .andExpect(status().isOk());
            save(body("053-123-4567", BusinessHourFixtures.nonOverlappingJson(22), repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].message").value("영업시간은 1 ~ 21줄이어야 합니다"));
        }

        @Test
        @DisplayName("영업시간이 겹치면 400 — 가입과 같은 겹침 검증이 붙어 있다")
        void rejectsOverlappingHours() throws Exception {
            save(body("053-123-4567", repeat(HOUR, 2), repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("businessHours"));
        }

        @Test
        @DisplayName("메뉴는 1~50개다")
        void menuCount() throws Exception {
            save(body("053-123-4567", repeat(HOUR, 1), "[]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("menus"));
            save(body("053-123-4567", repeat(HOUR, 1), distinctMenus(50))).andExpect(status().isOk());
            save(body("053-123-4567", repeat(HOUR, 1), distinctMenus(51)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].message").value("메뉴는 1 ~ 50개여야 합니다"));
        }

        @Test
        @DisplayName("영업시간·메뉴 한 줄 안의 오류는 몇 번째 줄의 어느 칸인지 응답에 실린다")
        void nestedErrorsPointToTheRow() throws Exception {
            String hours = "[" + HOUR + ",{\"day\":\"TUE\",\"open\":\"9:00\",\"close\":\"21:00\"}]";
            save(body("053-123-4567", hours, repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("businessHours[1].open"));

            save(body("053-123-4567", repeat(HOUR, 1), "[{\"name\":\"김치찌개\"}]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("menus[0].onSale"));

            String sameTimes = "[{\"day\":\"MON\",\"open\":\"12:00\",\"close\":\"12:00\"}]";
            save(body("053-123-4567", sameTimes, repeat(MENU, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("businessHours[0].openDifferentFromClose"));
        }

        @Test
        @DisplayName("목록 안에 null 이 섞이면 거절한다")
        void rejectsNullRows() throws Exception {
            save(body("053-123-4567", "[null]", repeat(MENU, 1))).andExpect(status().isBadRequest());
            save(body("053-123-4567", repeat(HOUR, 1), "[null]")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("요일은 MON~SUN 만 받는다 — 읽기 단계에서 걸려 칸별 errors 없이 invalid-request")
        void rejectsUnknownDay() throws Exception {
            for (String day : List.of("MONDAY", "월", "mon")) {
                String hours = "[{\"day\":\"" + day + "\",\"open\":\"11:00\",\"close\":\"21:00\"}]";
                save(body("053-123-4567", hours, repeat(MENU, 1)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.type").value(
                                "https://kakaotechcampus-4.github.io/ktc4-kyungpook-6/errors/invalid-request"));
            }
        }
    }
}
