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
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreMenuRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시연 흐름의 서버 쪽 전체를 실제 앱·DB 로 한 번에 확인한다.
 * <pre>
 * 카드 인증 → 주문 → 진행 중 주문 → 내 QR → 점주 QR 체크인(응답에 그 주문) → 조리 시작 → 아동에게 조리 중
 *   → 점주 목록 → 조리 완료 → 진행 중 없음 → 새 주문 가능 → 관리자 주문 내역
 * </pre>
 * 점주·가게·메뉴·연결은 이 흐름의 전제라 저장소로 바로 만든다. {@code @SpringBootTest} 는 롤백하지 않으므로 이 테스트가
 * 만든 행만 직접 지운다. 카드 번호는 모두 가짜 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("아동 주문부터 점주 조리 완료까지 통합")
class ChildOrderFlowIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

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
    private StoreMenuRepository storeMenuRepository;

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long storeId;
    private Long menuId;
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> childIds = new ArrayList<>();
    private String ownerBearer;
    private String adminBearer;

    @BeforeEach
    void setUp() {
        Store store = storeRepository.saveAndFlush(Store.builder()
                .name("주문흐름분식").nameNormalized("주문흐름분식")
                .addressRoad("가상특별시 예시구 주문흐름로 1").addressNormalized("가상특별시예시구주문흐름로1")
                .status(StoreStatus.OPEN)
                .build());
        storeId = store.getStoreId();
        menuId = storeMenuRepository.saveAndFlush(StoreMenu.of(store, "후라이드 치킨", true)).getMenuId();

        Member admin = memberRepository.saveAndFlush(Member.admin("order-flow-admin@example.com", "hash"));
        Member owner = Member.ownerApplicant("order-flow-owner@example.com", "hash", OwnerInfo.builder()
                .bizNo("0000000000").storeName("주문흐름분식").representativeName("홍길동").phone("01000000000").build());
        owner.approve(LINKED_AT);
        owner = memberRepository.saveAndFlush(owner);
        memberIds.add(owner.getMemberId());
        memberIds.add(admin.getMemberId());
        storeOwnerRepository.saveAndFlush(StoreOwner.link(store, owner, admin, LINKED_AT));

        ownerBearer = "Bearer " + jwtProvider.issue(owner.getMemberId(), MemberRole.OWNER).value();
        adminBearer = "Bearer " + jwtProvider.issue(admin.getMemberId(), MemberRole.ADMIN).value();
    }

    @AfterEach
    void cleanUp() {
        // 외래 키 순서대로 이 테스트가 만든 것만 지운다 — 주문, 체크인, QR, 아동, 메뉴, 연결, 회원, 가게
        jdbcTemplate.update("DELETE FROM child_order WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM check_in WHERE store_id = ?", storeId);
        for (Long childId : childIds) {
            jdbcTemplate.update("DELETE FROM qr_credential WHERE child_id = ?", childId);
            jdbcTemplate.update("DELETE FROM child WHERE child_id = ?", childId);
        }
        jdbcTemplate.update("DELETE FROM store_menu WHERE store_id = ?", storeId);
        jdbcTemplate.update("DELETE FROM store_owner WHERE store_id = ?", storeId);
        memberRepository.deleteAllById(memberIds);
        storeRepository.deleteById(storeId);
    }

    private JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    private String childBearer(String card) throws Exception {
        String body = mockMvc.perform(post("/api/auth/children/card").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + card + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json(body).get("accessToken").asText();
        childIds.add(jwtProvider.parse(token).orElseThrow().childId());
        return "Bearer " + token;
    }

    private String placeOrder(String child) throws Exception {
        return mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"menuId\":" + menuId + ",\"receiveType\":\"TAKEOUT\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode activeOrder(String child) throws Exception {
        String body = mockMvc.perform(get("/api/children/me/orders/active").header(HttpHeaders.AUTHORIZATION, child))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(body).get("order");
    }

    @Test
    @DisplayName("카드 → 주문 → 내 QR → 점주 스캔(그 주문이 함께 옴) → 조리 시작 → 조리 완료 → 다시 주문, 관리자 내역까지")
    void demoFlow() throws Exception {
        String child = childBearer("9200 0000 0000 0001");

        long orderId = json(placeOrder(child)).get("orderId").asLong();
        JsonNode active = activeOrder(child);
        assertThat(active.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(active.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(active.get("storeName").asText()).isEqualTo("주문흐름분식");

        // 두 번째 주문은 막힌다 — 버튼 연타·두 기기
        mockMvc.perform(post("/api/children/me/orders").header(HttpHeaders.AUTHORIZATION, child)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":" + storeId + ",\"menuId\":" + menuId + ",\"receiveType\":\"DINE_IN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.ACTIVE_ORDER_EXISTS.getType().toString()));

        String qr = json(mockMvc.perform(post("/api/children/me/qr-token").header(HttpHeaders.AUTHORIZATION, child))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("qrPayload").asText();

        mockMvc.perform(post("/api/stores/" + storeId + "/check-ins").header(HttpHeaders.AUTHORIZATION, ownerBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qrPayload\":\"" + qr + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.orderId").value(orderId))
                .andExpect(jsonPath("$.order.menuName").value("후라이드 치킨"));

        mockMvc.perform(post("/api/stores/" + storeId + "/orders/" + orderId + "/start-cooking")
                        .header(HttpHeaders.AUTHORIZATION, ownerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COOKING"));
        assertThat(activeOrder(child).get("status").asText()).isEqualTo("COOKING");
        assertThat(activeOrder(child).get("cookingStartedAt").isNull()).isFalse();

        mockMvc.perform(get("/api/stores/" + storeId + "/orders").header(HttpHeaders.AUTHORIZATION, ownerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].status").value("COOKING"));

        mockMvc.perform(post("/api/stores/" + storeId + "/orders/" + orderId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, ownerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(activeOrder(child).isNull()).isTrue();

        long nextOrderId = json(placeOrder(child)).get("orderId").asLong();
        assertThat(nextOrderId).isNotEqualTo(orderId);

        // 관리자 내역은 모든 가게의 주문이라, 다른 테스트의 주문과 섞여도 되게 번호로 찾는다
        mockMvc.perform(get("/api/admin/orders?limit=100").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.orderId == " + nextOrderId + ")].storeName").value("주문흐름분식"))
                .andExpect(jsonPath("$.content[?(@.orderId == " + orderId + ")].status").value("DONE"));
    }

    @Test
    @DisplayName("아이가 접수 대기 주문을 취소하면 점주 목록에서 취소로 보이고, 점주가 조리를 시작할 수 없다")
    void childCancels() throws Exception {
        String child = childBearer("9200 0000 0000 0002");
        long orderId = json(placeOrder(child)).get("orderId").asLong();

        mockMvc.perform(post("/api/children/me/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(get("/api/stores/" + storeId + "/orders").header(HttpHeaders.AUTHORIZATION, ownerBearer))
                .andExpect(jsonPath("$[0].status").value("CANCELED"));
        mockMvc.perform(post("/api/stores/" + storeId + "/orders/" + orderId + "/start-cooking")
                        .header(HttpHeaders.AUTHORIZATION, ownerBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorCode.ORDER_STATUS_CONFLICT.getType().toString()));
    }

    @Test
    @DisplayName("다른 아이는 남의 주문을 취소할 수 없다 — 404, 주문은 그대로")
    void otherChildCannotCancel() throws Exception {
        String child = childBearer("9200 0000 0000 0003");
        String other = childBearer("9200 0000 0000 0004");
        long orderId = json(placeOrder(child)).get("orderId").asLong();

        mockMvc.perform(post("/api/children/me/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ErrorCode.ORDER_NOT_FOUND.getType().toString()));
        assertThat(activeOrder(child).get("status").asText()).isEqualTo("RECEIVED");
    }
}
