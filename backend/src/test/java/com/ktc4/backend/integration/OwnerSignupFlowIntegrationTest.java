package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주가 가입 신청해서 로그인하기까지를 실제 앱·DB 로 한 번에 확인한다.
 * <pre>
 * 점주 가입 신청 → 로그인 시도(승인 대기 403) → 관리자 로그인 → 대기 목록 확인 → 승인 → 점주 로그인 → 내 정보
 * </pre>
 *
 * <p>관리자 계정은 실제 운영과 같은 경로(기동 시 환경변수)로 만든다. 권한 검사 스위치를 켜서 실제 규칙으로 돈다.
 * {@code @SpringBootTest} 는 롤백하지 않으므로 끝나면 회원 테이블을 비운다 — 같은 컨테이너를 쓰는 다른 테스트가
 * 빈 테이블을 전제하기 때문이다. 계정 정보는 모두 가짜 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("점주 가입부터 로그인까지 통합")
class OwnerSignupFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final String ADMIN_EMAIL = "flow-admin@example.com";
    private static final String ADMIN_PASSWORD = "flow-admin-password";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.enforce", () -> "true");
        registry.add("auth.admin.email", () -> ADMIN_EMAIL);
        registry.add("auth.admin.password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @AfterEach
    void cleanUp() {
        memberRepository.deleteAll();
    }

    private static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JSON.readTree(body).get("accessToken").asText();
    }

    @Test
    @DisplayName("가입 신청한 점주는 관리자가 승인한 뒤에야 로그인할 수 있다")
    void ownerCanLoginOnlyAfterApproval() throws Exception {
        // 1. 점주 가입 신청 — 사업자번호는 하이픈을 붙여 보내도 10자리로 저장된다
        String signupBody = mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-owner@example.com", "password", "owner-password",
                                "bizNo", "123-45-67890", "storeName", "예시분식", "representativeName", "홍길동")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long ownerId = JSON.readTree(signupBody).get("memberId").asLong();

        // 2. 승인 전 로그인 → 403 승인 대기
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-owner@example.com", "password", "owner-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/owner-pending-approval")));

        // 3. 관리자 로그인 → 대기 목록에 방금 신청이 보인다
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String listBody = mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode first = JSON.readTree(listBody).get("content").get(0);
        org.assertj.core.api.Assertions.assertThat(first.get("memberId").asLong()).isEqualTo(ownerId);
        org.assertj.core.api.Assertions.assertThat(first.get("bizNo").asText()).isEqualTo("1234567890");

        // 4. 승인 → 같은 신청을 다시 승인하면 409
        mockMvc.perform(post("/api/admin/owners/" + ownerId + "/approve").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/admin/owners/" + ownerId + "/approve").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());

        // 5. 점주 로그인 → 내 정보
        String owner = login("flow-owner@example.com", "owner-password");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 6. 점주 토큰으로 승인 API·관리자 API 는 403
        mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
    }
}
