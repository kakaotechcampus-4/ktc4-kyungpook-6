package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.file.repository.UploadFileRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.ktc4.backend.support.SignupMultipart.file;
import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.jpegOfSize;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static com.ktc4.backend.support.SignupMultipart.png;
import static com.ktc4.backend.support.SignupMultipart.signupRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주가 가입 신청해서 로그인하기까지를 실제 앱·DB 로 한 번에 확인한다.
 * <pre>
 * 파일 올리기(사업자등록증·대표 사진·메뉴 사진) → 받은 파일 ID 로 점주 가입 신청 → 가입 때 받은 토큰으로 상태 조회
 *   → 로그인 시도(승인 대기 403) → 관리자 로그인 → 대기 목록·첨부 확인 → 후보 가게 조회
 *   → 가게를 정해 승인(메뉴 사진도 가게 메뉴로 옮겨짐) → 점주 로그인 → 내 정보 → 가게 사진 보기 → 내 가게에만 체크인
 *   → 연결 끊기(체크인·사진 불가) → 다시 연결(체크인·사진 가능)
 * </pre>
 * 끝에 다른 신청자의 가입 거절도 확인한다 — 이미 쓴 파일 ID 로는 가입할 수 없고, 거절된 신청자는 로그인도, 승인도,
 * 가게 연결도 되지 않는다.
 *
 * <p>사진은 관리자와 그 가게에 연결된 점주만 본다. 사업자등록증은 관리자 경로로만 열리고, 가게 사진 경로에서는 없는 파일이다.
 *
 * <p>관리자 계정은 실제 운영과 같은 경로(기동 시 환경변수)로 만든다. 권한 검사도 실제 규칙으로 돈다.
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
        registry.add("auth.admin.email", () -> ADMIN_EMAIL);
        registry.add("auth.admin.password", () -> ADMIN_PASSWORD);
        // 가입하면 파일이 실제로 디스크에 쓰인다. 프로젝트 폴더가 아니라 임시 폴더에 쓰게 한다
        registry.add("upload.dir", UPLOAD_DIR::toString);
    }

    private static final Path UPLOAD_DIR = newTempDir();

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("ktc4-upload-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private UploadFileRepository uploadFileRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StoreOwnerRepository storeOwnerRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdStoreIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 외래 키 순서대로 지운다 — 연결, 파일 기록(회원을 가리킴), 가게의 영업시간·메뉴(승인 때 옮겨 적힌 것), 회원, 가게.
        // 아무 신청도 가져가지 않은 업로드 기록도 지운다
        storeOwnerRepository.deleteAll();
        uploadFileRepository.deleteAll();
        jdbcTemplate.update("delete from pending_upload");
        for (Long storeId : createdStoreIds) {
            jdbcTemplate.update("delete from store_business_hour where store_id = ?", storeId);
            jdbcTemplate.update("delete from store_menu where store_id = ?", storeId);
        }
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

    // 가입 신청 본문. 가게 정보(주소·가게 전화·영업시간·메뉴)는 필수라 늘 같은 값을 붙인다.
    // 파일은 업로드 API 로 먼저 올리고 받은 ID 만 싣는다 — 사업자등록증은 필수, 대표 사진·메뉴 사진은 null 이면 뺀다
    private static String signupJson(String email, String phone, String bizCertFileId,
                                     String storeImageFileId, String menuPhotoFileId) {
        String account = json("email", email, "password", "owner-password", "bizNo", "123-45-67890",
                "storeName", "예시분식", "representativeName", "홍길동", "phone", phone,
                "storeAddress", "가상특별시 예시구 샘플로 123", "storePhone", "053-000-0009",
                "bizCertFileId", bizCertFileId);
        return account.substring(0, account.length() - 1)
                + (storeImageFileId == null ? "" : ",\"storeImageFileId\":\"" + storeImageFileId + "\"")
                + ",\"businessHours\":[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}]"
                + ",\"menus\":[{\"name\":\"김치찌개\""
                + (menuPhotoFileId == null ? "" : ",\"photoFileId\":\"" + menuPhotoFileId + "\"") + "}]}";
    }

    // 파일 업로드 API 로 올리고(토큰 없이) 받은 파일 ID 를 돌려준다
    private String upload(String kind, MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/files").file(file).param("kind", kind))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body).get("fileId").asText();
    }

    // 사람 이름이 든 파일 이름으로 거절될 파일을 올리고, 응답과 그동안 남은 모든 로그에 그 이름이 없는지 본다
    private void assertRejectedUploadLeavesNoFilename(String filename, MockMultipartFile file, String kind, int status)
            throws Exception {
        MockMultipartFile named = new MockMultipartFile(file.getName(), filename, file.getContentType(), file.getBytes());
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        String body;
        try {
            body = mockMvc.perform(multipart("/api/files").file(named).param("kind", kind))
                    .andExpect(status().is(status))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        } finally {
            root.detachAppender(logs);
        }
        String stem = filename.substring(0, filename.lastIndexOf('.'));
        assertThat(body).doesNotContain("홍길동").doesNotContain(stem);
        assertThat(logs.list).isNotEmpty()   // 거절 로그는 남는다 — 이름만 빠진다
                .noneMatch(event -> event.getFormattedMessage().contains("홍길동"))
                .noneMatch(event -> event.getThrowableProxy() != null
                        && String.valueOf(event.getThrowableProxy().getMessage()).contains("홍길동"));
    }

    private long pendingUploadCount() {
        return jdbcTemplate.queryForObject("select count(*) from pending_upload", Long.class);
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

        // 0. 가입 전에 파일부터 올린다(토큰 없이). 형식은 이름이 아니라 내용으로 가린다 — 이름만 photo.jpg 인 HTML 은 거절한다
        long pendingBefore = pendingUploadCount();
        mockMvc.perform(multipart("/api/files")
                        .file(file("file", "photo.jpg", MediaType.IMAGE_JPEG_VALUE,
                                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))
                        .param("kind", "STORE_IMAGE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("/unsupported-file-type")));
        // 0-1. 거절된 파일의 이름은 응답에도, 어느 로그(검사기·에러 처리 포함)에도 남지 않는다 — 사람 이름이 들어 있을 수 있다
        assertRejectedUploadLeavesNoFilename("홍길동_사업자등록증_원본.pdf",
                file("file", "홍길동_사업자등록증_원본.pdf", MediaType.APPLICATION_PDF_VALUE,
                        "not a pdf at all".getBytes(StandardCharsets.UTF_8)), "BIZ_CERT", 400);
        assertRejectedUploadLeavesNoFilename("홍길동_가게사진.jpg",
                jpegOfSize("file", 5 * 1024 * 1024 + 1), "STORE_IMAGE", 413);
        String bizCertId = upload("BIZ_CERT", pdf("file"));
        String storeImageId = upload("STORE_IMAGE", jpeg("file"));
        String menuPhotoId = upload("MENU_IMAGE", png("file"));
        assertThat(pendingUploadCount()).isEqualTo(pendingBefore + 3);   // 거절된 파일은 남지 않는다

        // 1. 점주 가입 신청 — 사업자번호는 하이픈을 붙여 보내도 10자리로 저장된다.
        //    실은 파일은 이 신청자의 파일이 되고 대기 기록에서 빠진다
        String signupBody = mockMvc.perform(signupRequest(
                        signupJson("flow-owner@example.com", "010-0000-0001", bizCertId, storeImageId, menuPhotoId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long ownerId = JSON.readTree(signupBody).get("memberId").asLong();
        String statusToken = "Bearer " + JSON.readTree(signupBody).get("statusToken").asText();
        assertThat(pendingUploadCount()).isEqualTo(pendingBefore);

        // 1-1. 가입 때 받은 토큰으로는 자기 가입 상태만 읽는다 — 체크인도, 관리자 API 도 부를 수 없다
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, statusToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(ownerId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stores.length()").value(0));   // 승인 전이라 연결된 가게가 없다
        mockMvc.perform(post("/api/stores/" + myStoreId + "/check-ins").header(HttpHeaders.AUTHORIZATION, statusToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("qrPayload", "v1.!!!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
        mockMvc.perform(post("/api/admin/owners/" + ownerId + "/approve").header(HttpHeaders.AUTHORIZATION, statusToken)
                        .contentType(MediaType.APPLICATION_JSON).content(storeIdBody(myStoreId)))
                .andExpect(status().isForbidden());

        // 1-2. 승인 전에는 신청자도 자기가 올린 파일을 열지 못한다 — 가입 상태 토큰은 403, 토큰이 없으면 401.
        //      사업자등록증과 첨부 목록은 관리자 경로라 역시 막힌다
        for (String path : List.of("/api/files/" + storeImageId, "/api/stores/" + myStoreId + "/images",
                "/api/admin/files/" + bizCertId, "/api/admin/owners/" + ownerId + "/files")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, statusToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized());
        }

        // 2. 승인 전 로그인 → 403 승인 대기
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "flow-owner@example.com", "password", "owner-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/owner-pending-approval")));

        // 3. 관리자 로그인 → 대기 목록에 방금 신청이 보인다. 관리자는 가게에 연결되지 않으므로 내 정보의 가게는 비어 있다
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores.length()").value(0));
        String listBody = mockMvc.perform(get("/api/admin/owners").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode first = JSON.readTree(listBody).get("content").get(0);
        org.assertj.core.api.Assertions.assertThat(first.get("memberId").asLong()).isEqualTo(ownerId);
        org.assertj.core.api.Assertions.assertThat(first.get("bizNo").asText()).isEqualTo("1234567890");
        org.assertj.core.api.Assertions.assertThat(first.get("phone").asText()).isEqualTo("01000000001");
        // 가입 때 적은 가게 정보가 신청서에 저장돼 그대로 보인다
        org.assertj.core.api.Assertions.assertThat(first.get("storeAddress").asText()).isEqualTo("가상특별시 예시구 샘플로 123");
        org.assertj.core.api.Assertions.assertThat(first.get("storePhone").asText()).isEqualTo("053-000-0009");
        org.assertj.core.api.Assertions.assertThat(first.get("businessHours").toString())
                .isEqualTo("[{\"day\":\"MON\",\"open\":\"11:00\",\"close\":\"21:00\"}]");
        org.assertj.core.api.Assertions.assertThat(first.get("menus").toString())
                .isEqualTo("[{\"name\":\"김치찌개\",\"photoFileId\":\"" + menuPhotoId + "\"}]");

        // 3-1. 관리자는 신청에 붙은 파일 셋을 보고, 사업자등록증을 내려받는다
        mockMvc.perform(get("/api/admin/owners/" + ownerId + "/files").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].fileId", containsInAnyOrder(bizCertId, storeImageId, menuPhotoId)))
                .andExpect(jsonPath("$[*].kind", containsInAnyOrder("BIZ_CERT", "STORE_IMAGE", "MENU_IMAGE")));
        mockMvc.perform(get("/api/admin/files/" + bizCertId).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")));

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

        // 6-0-1. 승인하면 신청서의 영업시간·메뉴가 비어 있던 가게에 옮겨 적힌다. 가게 전화는 이미 있어 그대로다
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForList(
                        "select day, open_minute, close_minute from store_business_hour where store_id = ?", myStoreId))
                .containsExactly(java.util.Map.of("day", "MON", "open_minute", 660, "close_minute", 1260));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForList(
                        "select name from store_menu where store_id = ? and on_sale", String.class, myStoreId))
                .containsExactly("김치찌개");
        assertThat(jdbcTemplate.queryForList(
                        "select photo_file_id from store_menu where store_id = ? and on_sale", String.class, myStoreId))
                .containsExactly(menuPhotoId);   // 메뉴 사진도 함께 옮겨진다
        org.assertj.core.api.Assertions.assertThat(storeRepository.findById(myStoreId).orElseThrow().getPhone())
                .isEqualTo("053-000-0001");
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
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.stores.length()").value(1))   // 승인 때 고른 가게 — 점주 앱은 이 번호로 체크인한다
                .andExpect(jsonPath("$.stores[0].storeId").value(myStoreId))
                .andExpect(jsonPath("$.stores[0].name").value("통합테스트전용가게"));

        // 7-1. 연결된 점주는 내 가게 사진을 본다 — 보는 사람 브라우저에만 잠깐 두고, 토큰 없이는 401.
        //      사업자등록증은 사진 경로에서 없는 파일이다(관리자도 같다). 관리자 경로로 열려 하면 점주는 403
        mockMvc.perform(get("/api/stores/" + myStoreId + "/images").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].fileId", containsInAnyOrder(storeImageId, menuPhotoId)));
        mockMvc.perform(get("/api/files/" + storeImageId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")));
        mockMvc.perform(get("/api/files/" + menuPhotoId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mockMvc.perform(get("/api/files/" + storeImageId))
                .andExpect(status().isUnauthorized());
        for (String token : List.of(owner, admin)) {
            mockMvc.perform(get("/api/files/" + bizCertId).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(endsWith("/file-not-found")));
        }
        mockMvc.perform(get("/api/admin/files/" + bizCertId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stores/" + otherStoreId + "/images").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());

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

        // 9-1. 가게 관리 — 내 가게 상세에는 승인 때 옮겨 적힌 영업시간·메뉴가 보인다. 저장하면 다시 읽어도 그대로다.
        //      남의 가게·없는 가게는 점주에게 403(가게가 있는지 알려 주지 않는다), 관리자는 없는 가게에 404
        String myStore = "/api/stores/" + myStoreId;
        String detailBody = mockMvc.perform(get(myStore).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("053-000-0001"))
                .andExpect(jsonPath("$.businessHours[0].day").value("MON"))
                .andExpect(jsonPath("$.businessHours[0].close").value("21:00"))
                .andExpect(jsonPath("$.menus[0].name").value("김치찌개"))
                .andExpect(jsonPath("$.menus[0].photoFileId").value(menuPhotoId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long menuId = JSON.readTree(detailBody).get("menus").get(0).get("menuId").asLong();
        String operatingInfo = "{\"phone\":\" 053-777-0000 \","
                + "\"businessHours\":[{\"day\":\"SUN\",\"open\":\"10:00\",\"close\":\"14:00\"},"
                + "{\"day\":\"MON\",\"open\":\"21:00\",\"close\":\"02:00\"}],"
                + "\"menus\":[{\"menuId\":" + menuId + ",\"name\":\"김치찌개 특\",\"onSale\":false},"
                + "{\"name\":\"라면\",\"onSale\":true}]}";
        for (var result : List.of(
                mockMvc.perform(put(myStore + "/operating-info").header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(operatingInfo)),
                mockMvc.perform(get(myStore).header(HttpHeaders.AUTHORIZATION, owner)))) {
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value("053-777-0000"))
                    .andExpect(jsonPath("$.businessHours[0].day").value("MON"))     // 요일 순 — 보낸 순서가 아니다
                    .andExpect(jsonPath("$.businessHours[0].close").value("02:00"))
                    .andExpect(jsonPath("$.businessHours[1].day").value("SUN"))
                    .andExpect(jsonPath("$.menus.length()").value(2))
                    .andExpect(jsonPath("$.menus[0].menuId").value(menuId))
                    .andExpect(jsonPath("$.menus[0].name").value("김치찌개 특"))
                    .andExpect(jsonPath("$.menus[0].onSale").value(false))
                    .andExpect(jsonPath("$.menus[0].photoFileId").value(menuPhotoId))   // 이름을 고쳐도 사진은 남는다
                    .andExpect(jsonPath("$.menus[1].name").value("라면"));
        }
        for (String path : List.of("/api/stores/" + otherStoreId, "/api/stores/" + MISSING_STORE_ID)) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, owner))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/forbidden")));
            mockMvc.perform(put(path + "/operating-info").header(HttpHeaders.AUTHORIZATION, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(operatingInfo))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/stores/" + MISSING_STORE_ID).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/store-not-found")));
        mockMvc.perform(get("/api/stores/" + otherStoreId).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

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
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.stores.length()").value(0));   // 끊긴 연결은 내 가게에서 빠진다
        // 사진도 그 즉시 내려간다 — 목록은 403, 파일은 없는 파일과 같은 404
        mockMvc.perform(get("/api/stores/" + myStoreId + "/images").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/files/" + storeImageId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("/file-not-found")));
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
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(jsonPath("$.stores[0].storeId").value(myStoreId));
        mockMvc.perform(get("/api/files/" + storeImageId).header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isOk());
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
        // 12-0. 앞 신청이 이미 가져간 사업자등록증 ID 로는 가입할 수 없다 — 400 이고 계정도 만들어지지 않는다
        long membersBefore = memberRepository.count();
        mockMvc.perform(signupRequest(signupJson("flow-rejected@example.com", "010-0000-0003", bizCertId, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("/invalid-file-reference")));
        assertThat(memberRepository.count()).isEqualTo(membersBefore);
        // 새로 올리면 같은 이메일로 가입된다 — 앞 시도가 아무것도 남기지 않았다. 사업자등록증은 휴대폰 사진이어도 된다
        String rejectedBizCertId = upload("BIZ_CERT", jpeg("file"));
        String rejectedSignupBody = mockMvc.perform(signupRequest(
                        signupJson("flow-rejected@example.com", "010-0000-0003", rejectedBizCertId, null, null)))
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
