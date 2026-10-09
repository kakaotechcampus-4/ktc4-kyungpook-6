package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.repository.StoreOwnerRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.repository.StoreRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주가 가입 신청해서 로그인하기까지를 실제 앱·DB 로 한 번에 확인한다.
 * <pre>
 * 점주 가입 신청 → 가입 때 받은 토큰으로 상태 조회 → 로그인 시도(승인 대기 403) → 관리자 로그인 → 대기 목록 확인 → 후보 가게 조회
 *   → 가게를 정해 승인 → 점주 로그인 → 내 정보 → 내 가게에만 체크인
 *   → 연결 끊기(체크인 불가) → 다시 연결(체크인 가능)
 * </pre>
 * 끝에 다른 신청자의 가입 거절도 확인한다 — 거절된 신청자는 로그인도, 승인도, 가게 연결도 되지 않는다.
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
    private static final long MISSING_STORE_ID = 999_999L;

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

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private StoreRepository storeRepository;

    private final List<Long> createdStoreIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 연결이 회원·가게를 가리키므로 연결부터 지운다
        storeOwnerRepository.deleteAll();
        memberRepository.deleteAll();
        storeRepository.deleteAllById(createdStoreIds);
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

    private static String storeIdBody(long storeId) {
        return "{\"storeId\":" + storeId + "}";
    }

    // phone 은 가게 전화번호(매장 번호), ownerPhone 은 가게를 등록할 때 적은 점주 개인 번호다
    private long saveStore(String name, String bizNo, String phone, String ownerPhone) {
        Store store = storeRepository.save(Store.builder()
                .name(name)
                .nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .bizNo(bizNo)
                .phone(phone)
                .ownerPhone(ownerPhone)
                .build());
        createdStoreIds.add(store.getStoreId());
        return store.getStoreId();
    }

    @Test
    @DisplayName("가입 신청한 점주는 관리자가 가게를 정해 승인한 뒤에야 로그인하고, 그 가게에만 체크인할 수 있다")
    void ownerCanLoginOnlyAfterApproval() throws Exception {
        // 내 가게는 사업자번호가 비어 있고, 가게를 등록할 때 적은 점주 휴대폰 번호만 있다 — 그 번호로 후보에 올라야 한다.
        // 다른 가게는 점주 휴대폰 번호가 비어 있고, 매장 전화번호가 우연히 신청서의 번호와 같다 — 후보에 오르면 안 된다
        long myStoreId = saveStore("통합테스트전용가게", null, "053-000-0001", "010 0000 0001");
        long otherStoreId = saveStore("통합테스트다른가게", "2222222222", "010-0000-0001", null);

        // 1. 점주 가입 신청 — 사업자번호는 하이픈을 붙여 보내도 10자리로 저장된다
        String signupBody = mockMvc.perform(post("/api/auth/owners/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-owner@example.com", "password", "owner-password",
                                "bizNo", "123-45-67890", "storeName", "예시분식", "representativeName", "홍길동",
                                "phone", "010-0000-0001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long ownerId = JSON.readTree(signupBody).get("memberId").asLong();
        String statusToken = "Bearer " + JSON.readTree(signupBody).get("statusToken").asText();

        // 1-1. 가입 때 받은 토큰으로는 자기 가입 상태만 읽는다 — 체크인도, 관리자 API 도 부를 수 없다
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, statusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(ownerId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, statusToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("qrPayload", "v1.!!!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
        mockMvc.perform(post("/api/admin/owners/" + ownerId + "/approve").header(HttpHeaders.AUTHORIZATION, statusToken)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(myStoreId)))
                .andExpect(status().isForbidden());

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
        org.assertj.core.api.Assertions.assertThat(first.get("phone").asText()).isEqualTo("01000000001");

        // 4. 후보 가게 조회 — 사업자번호도 이름도 다르지만, 신청서의 휴대폰 번호가 가게에 등록된 점주 휴대폰 번호와 같아
        //    후보에 오른다. 표기(하이픈·공백)가 달라도 같은 번호로 본다. 아직 연결된 점주는 없다.
        //    매장 전화번호만 같은 다른 가게는 후보가 아니다 — 후보는 한 곳뿐이다
        String candidates = "/api/admin/owners/" + ownerId + "/store-candidates";
        mockMvc.perform(get(candidates).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].storeId").value(myStoreId))
                .andExpect(jsonPath("$[0].bizNoMatched").value(false))
                .andExpect(jsonPath("$[0].phoneMatched").value(true))
                .andExpect(jsonPath("$[0].nameMatch").value("NONE"))
                .andExpect(jsonPath("$[0].phone").value("053-000-0001"))   // 매장 번호는 그대로 — 관리자가 확인 전화를 건다
                .andExpect(jsonPath("$[0].ownerPhone").doesNotExist())     // 점주 개인 번호는 내려주지 않는다
                .andExpect(jsonPath("$[0].ntsState").doesNotExist())       // 국세청 확인 전인 가게
                .andExpect(jsonPath("$[0].linkedOwnerCount").value(0));

        // 5. 가게 없이는 승인할 수 없다 — 본문이 없어도, 없는 가게여도. 신청은 승인 대기 그대로 남는다
        String approve = "/api/admin/owners/" + ownerId + "/approve";
        mockMvc.perform(post(approve).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(approve).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(MISSING_STORE_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/store-not-found")));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-owner@example.com", "password", "owner-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/owner-pending-approval")));
        org.assertj.core.api.Assertions.assertThat(storeOwnerRepository.count()).isZero();

        // 6. 가게를 골라 승인 → 같은 신청을 다시 승인하면 409 이고 연결은 한 건뿐
        mockMvc.perform(post(approve).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(myStoreId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post(approve).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(otherStoreId)))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(storeOwnerRepository.count()).isEqualTo(1);
        mockMvc.perform(get(candidates).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[0].linkedOwnerCount").value(1));

        // 6-1. 가입 때 받은 토큰으로 다시 조회하면 승인된 것이 보인다. 그래도 이 토큰은 접근 토큰이 되지 않는다 —
        //      가게가 연결된 뒤에도 내 가게에 체크인할 수 없고, 로그인해야 한다
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, statusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, statusToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("qrPayload", "v1.!!!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));

        // 7. 점주 로그인 → 내 정보
        String owner = login("flow-owner@example.com", "owner-password");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 8. 점주 토큰으로 승인 API·관리자 API 는 403
        mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(candidates).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());

        // 9. 체크인 — 남의 가게는 QR 을 보기 전에 403. 없는 가게 번호여도 점주에게는 똑같이 403 이다(가게가 있는지 알려 주지 않는다).
        //    내 가게는 연결 확인을 지나 QR 검사까지 간다(틀린 QR 이라 400)
        String wrongQr = json("qrPayload", "v1.!!!");
        mockMvc.perform(post("/api/stores/" + otherStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
        mockMvc.perform(post("/api/stores/" + MISSING_STORE_ID + "/check-ins").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/invalid-qr-token")));
        // 관리자는 연결이 없어도 어느 가게든 QR 검사까지 간다
        mockMvc.perform(post("/api/stores/" + otherStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/invalid-qr-token")));

        // 10. 연결을 끊으면 이미 받은 토큰으로도 그 즉시 내 가게에 체크인할 수 없다. 행은 지워지지 않고,
        //     후보 조회의 연결된 점주 수에서는 빠진다. 승인 때 가게에 적힌 내 휴대폰 번호도 지워져 더는 번호로 후보에 오르지 않는다
        String link = "/api/admin/owners/" + ownerId + "/stores/" + myStoreId;
        mockMvc.perform(delete(link).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());   // 점주가 스스로 끊거나 이을 수 없다
        mockMvc.perform(delete(link).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
        org.assertj.core.api.Assertions.assertThat(storeOwnerRepository.count()).isEqualTo(1);
        // 가게에 적혀 있던 번호가 이 점주의 번호(표기만 다름)라 함께 지워졌다 — 번호로는 더 이상 후보가 아니다
        mockMvc.perform(get(candidates).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // 이미 끊긴 연결을 또 끊으면 404
        mockMvc.perform(delete(link).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/store-link-not-found")));

        // 11. 다시 연결하면 체크인이 QR 검사까지 간다. 같은 가게에 또 연결하면 409 이고 행은 하나 그대로다
        mockMvc.perform(post(link).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongQr))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/invalid-qr-token")));
        mockMvc.perform(post(link).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/store-already-linked")));
        org.assertj.core.api.Assertions.assertThat(storeOwnerRepository.count()).isEqualTo(1);

        // 12. 다른 사람이 가입을 신청하고 관리자가 거절한다. 같은 테스트에 두는 이유: 끝나고 회원 테이블을 비우면
        //     기동할 때 만든 관리자 계정도 지워져, 테스트 메서드를 나누면 두 번째가 관리자로 로그인하지 못한다
        long storeId = otherStoreId;
        String rejectedSignupBody = mockMvc.perform(post("/api/auth/owners/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-rejected@example.com", "password", "owner-password",
                                "bizNo", "123-45-67890", "storeName", "예시분식", "representativeName", "홍길동",
                                "phone", "010-0000-0003")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long applicantId = JSON.readTree(rejectedSignupBody).get("memberId").asLong();

        // 거절 → 거절 목록에 보이고, 대기 목록에서는 빠진다
        mockMvc.perform(post("/api/admin/owners/" + applicantId + "/reject").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedAt").exists());
        mockMvc.perform(get("/api/admin/owners").param("status", "REJECTED").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[0].memberId").value(applicantId));
        mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(0));

        // 로그인하면 403 거절 안내
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-rejected@example.com", "password", "owner-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/owner-rejected")));

        // 거절된 신청은 승인할 수도, 다시 거절할 수도, 가게에 연결할 수도 없다
        mockMvc.perform(post("/api/admin/owners/" + applicantId + "/approve").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(storeId)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/owners/" + applicantId + "/reject").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/owners/" + applicantId + "/stores/" + storeId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/owner-not-approved")));
        // 연결은 앞에서 만든 한 건 그대로다 — 거절된 신청자에게는 연결이 생기지 않았다
        org.assertj.core.api.Assertions.assertThat(storeOwnerRepository.count()).isEqualTo(1);
    }
}
