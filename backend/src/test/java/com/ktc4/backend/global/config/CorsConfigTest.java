package com.ktc4.backend.global.config;

import com.ktc4.backend.domain.store.controller.StoreController;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 허용한 프론트 주소에서 온 사전 요청(OPTIONS)만 통과하는지 확인.
 *
 * <p>사전 요청에는 토큰이 실리지 않으므로, 권한 검사를 켠 상태에서도 인증에 막히지 않아야 한다.
 */
@WebMvcTest(StoreController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@TestPropertySource(properties = {"auth.enforce=true", "cors.allowed-origins=" + CorsConfigTest.ALLOWED_ORIGIN})
@DisplayName("CORS 허용 출처")
class CorsConfigTest {

    static final String ALLOWED_ORIGIN = "https://goodradar.mojan.kr";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @Test
    @DisplayName("허용한 출처의 사전 요청은 토큰 없이도 통과하고 허용 헤더가 붙는다")
    void allowedOriginPreflight() throws Exception {
        mockMvc.perform(options("/api/stores")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("허용하지 않은 출처의 사전 요청은 403 으로 막힌다")
    void unknownOriginPreflight() throws Exception {
        mockMvc.perform(options("/api/stores")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
