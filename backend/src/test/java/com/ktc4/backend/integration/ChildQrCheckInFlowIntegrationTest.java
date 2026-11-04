package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.entity.StoreOwner;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.JwtProvider;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 아동이 급식카드로 인증해 내 QR 을 받고, 점주가 그 QR 을 찍어 체크인하기까지를 실제 앱·DB 로 한 번에 확인한다.
 * <pre>
 * 카드 인증 → 아동 토큰 → 내 QR 발급 → 점주가 그 QR 로 체크인(201)
 *   → 같은 카드로 다시 인증(같은 아동) → QR 재발급 → 옛 QR 체크인 400, 새 QR 201
 *   → 다른 카드는 다른 아동 → DB 에는 카드 번호가 남지 않는다
 * </pre>
 * 점주·가게·연결은 이 흐름의 전제라 저장소로 바로 만든다(가입·승인 흐름은 {@code OwnerSignupFlowIntegrationTest} 가 본다).
 * {@code @SpringBootTest} 는 롤백하지 않으므로 이 테스트가 만든 행만 직접 지운다. 카드 번호는 모두 가짜 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("아동 카드 인증부터 QR 체크인까지 통합")
class ChildQrCheckInFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final String CARD = "9100 0000 0000 0001";
    private static final String SAME_CARD_UNSPACED = "9100000000000001";
    private static final String OTHER_CARD = "9100-0000-0000-0002";
    private static final LocalDateTime LINKED_AT = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("child.card-hash-secret", () -> "test-only-card-hash-secret-at-least-32-bytes");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long storeId;
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> childIds = new ArrayList<>();
    private String ownerBearer;

    @BeforeEach
    void setUp() {
        Store store = storeRepository.saveAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 아동흐름로 1")
                .addressNormalized("가상특별시예시구아동흐름로1")
                .status(StoreStatus.OPEN)
                .build());
        storeId = store.getStoreId();

        Member admin = memberRepository.saveAndFlush(Member.admin("child-flow-admin@example.com", "hash"));
        Member owner = Member.ownerApplicant("child-flow-owner@example.com", "hash", OwnerInfo.builder()
                .bizNo("0000000000").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                .build());
        owner.approve(LINKED_AT);
        owner = memberRepository.saveAndFlush(owner);
        memberIds.add(owner.getMemberId());
        memberIds.add(admin.getMemberId());
        storeOwnerRepository.saveAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));

        ownerBearer = "Bearer " + jwtProvider.issue(owner.getMemberId(), MemberRole.OWNER).value();
    }

    @AfterEach
    void cleanUp() {
        // 외래 키 순서대로 이 테스트가 만든 것만 지운다 — 체크인, QR, 아동, 연결, 회원, 가게
        jdbcTemplate.update("DELETE FROM check_in WHERE store_id = ?", storeId);
        for (Long childId : childIds) {
            jdbcTemplate.update("DELETE FROM qr_credential WHERE child_id = ?", childId);
            jdbcTemplate.update("DELETE FROM child WHERE child_id = ?", childId);
        }
        jdbcTemplate.update("DELETE FROM store_owner WHERE store_id = ?", storeId);
        memberRepository.deleteAllById(memberIds);
        storeRepository.deleteById(storeId);
    }

    /** 카드로 인증해 받은 Bearer 헤더 값. 토큰의 아동 번호는 정리용으로 모아 둔다. */
    private String authenticate(String card) throws Exception {
        String body = mockMvc.perform(post("/api/auth/children/card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + card + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = JSON.readTree(body);
        // 실제 서비스가 만든 응답에도 아동 번호가 없다 — 토큰 세 칸뿐
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt");
        String token = json.get("accessToken").asText();
        Long childId = jwtProvider.parse(token).orElseThrow().childId();
        if (!childIds.contains(childId)) {
            childIds.add(childId);
        }
        return "Bearer " + token;
    }

    private Long childIdOf(String bearer) {
        return jwtProvider.parse(bearer.substring("Bearer ".length())).orElseThrow().childId();
    }

    private String issueMyQr(String childBearer) throws Exception {
        String body = mockMvc.perform(post("/api/children/me/qr-token").header(HttpHeaders.AUTHORIZATION, childBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = JSON.readTree(body);
        return json.get("qrPayload").asText();
    }

    private ResultActions checkIn(String qrPayload) throws Exception {
        return mockMvc.perform(post("/api/stores/" + storeId + "/check-ins")
                .header(HttpHeaders.AUTHORIZATION, ownerBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + qrPayload + "\"}"));
    }

    @Test
    @DisplayName("카드 인증 → 내 QR → 점주 체크인, 같은 카드 재인증·재발급이면 옛 QR 은 거절되고 새 QR 은 된다")
    void cardToCheckIn() throws Exception {
        String child = authenticate(CARD);
        String firstQr = issueMyQr(child);
        checkIn(firstQr).andExpect(status().isCreated());

        // 같은 카드를 붙여 써서 다시 인증해도 같은 아동이다
        String again = authenticate(SAME_CARD_UNSPACED);
        assertThat(childIdOf(again)).isEqualTo(childIdOf(child));

        String secondQr = issueMyQr(again);
        assertThat(secondQr).isNotEqualTo(firstQr);
        checkIn(firstQr).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ErrorCode.INVALID_QR_TOKEN.getType().toString()));
        checkIn(secondQr).andExpect(status().isCreated());

        Integer checkIns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM check_in WHERE store_id = ? AND child_id = ?", Integer.class, storeId, childIdOf(child));
        assertThat(checkIns).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 카드는 다른 아동이고, 그 아동의 QR 로 남긴 체크인은 그 아동 번호로 남는다")
    void otherCardIsOtherChild() throws Exception {
        String first = authenticate(CARD);
        String other = authenticate(OTHER_CARD);
        assertThat(childIdOf(other)).isNotEqualTo(childIdOf(first));

        checkIn(issueMyQr(other)).andExpect(status().isCreated());

        Long recorded = jdbcTemplate.queryForObject(
                "SELECT child_id FROM check_in WHERE store_id = ?", Long.class, storeId);
        assertThat(recorded).isEqualTo(childIdOf(other));
    }

    @Test
    @DisplayName("DB 에는 카드 번호가 남지 않는다 — 해시 칸에도 번호 조각이 보이지 않는다")
    void cardNumberIsNotStored() throws Exception {
        Long childId = childIdOf(authenticate(CARD));

        String stored = jdbcTemplate.queryForObject("SELECT card_hash FROM child WHERE child_id = ?", String.class, childId);
        assertThat(stored).matches("[0-9a-f]{64}").doesNotContain("9100").doesNotContain("0001");
    }
}
