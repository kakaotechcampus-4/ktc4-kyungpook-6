package com.ktc4.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스웨거 문서(`/v3/api-docs`)가 실제 응답과 같은 모양으로 나오는지 본다.
 *
 * <p><b>왜 필요한가</b> — 프론트는 이 문서에서 타입을 만든다(Orval). 문서가 틀리면 컴파일은 통과하는데
 * 화면에서 값이 비는 식으로 어긋나고, 그건 돌려 보기 전에는 안 보인다.
 *
 * <p>실제로 겹친 적이 있다. {@code auth.dto.LinkedStoreResponse}(가게 ID·이름)와
 * {@code member.dto.LinkedStoreResponse}(주소·연결 시각까지)가 패키지만 달랐는데, springdoc 은 스키마
 * 이름을 **클래스 단순 이름**으로 짓기 때문에 둘이 한 이름으로 겹쳐 하나가 통째로 덮였다. 관리자 API
 * 응답이 두 칸짜리로 문서화됐고, 프론트에는 {@code addressRoad}·{@code linkedAt} 이 없는 타입이 생겼다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("스웨거 문서")
class ApiDocsSchemaIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private JsonNode apiDocs() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    @Test
    @DisplayName("연결된 가게 조회는 주소와 연결 시각까지 문서에 나온다")
    void linkedStoreDetailKeepsItsOwnColumns() throws Exception {
        JsonNode docs = apiDocs();
        // content 의 미디어 타입 키는 스프링이 정한다(지금은 `*/*`). 타입을 고정하지 않고 $ref 만 찾는다.
        JsonNode content = docs.at(
                "/paths/~1api~1admin~1owners~1{memberId}~1stores/get/responses/200/content");
        assertThat(content.isObject()).isTrue();

        Set<String> refs = new HashSet<>();
        collectRefs(content, refs);
        assertThat(refs).hasSize(1);

        JsonNode schema = docs.at("/components/schemas/" + refs.iterator().next());
        assertThat(schema.get("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("storeId", "name", "addressRoad", "linkedAt");
    }

    @Test
    @DisplayName("내 정보의 연결된 가게는 가게 ID·이름 두 칸 그대로다")
    void memberResponseKeepsTheTwoColumnShape() throws Exception {
        JsonNode docs = apiDocs();
        String ref = docs.at("/components/schemas/MemberResponse/properties/stores/items/$ref").asText();
        JsonNode schema = docs.at("/components/schemas/" + ref.substring(ref.lastIndexOf('/') + 1));

        assertThat(schema.get("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("storeId", "name");
    }

    @Test
    @DisplayName("사업자등록증 읽기는 아직 501 이어도 응답 모양(200)이 문서에 나오고, 토큰 없이 부른다고 적힌다")
    void bizCertExtractionPublishesItsShape() throws Exception {
        JsonNode docs = apiDocs();
        JsonNode operation = docs.at("/paths/~1api~1files~1{fileId}~1biz-cert-extraction/post");
        assertThat(operation.isObject()).isTrue();

        Set<String> refs = new HashSet<>();
        collectRefs(operation.at("/responses/200/content"), refs);
        assertThat(refs).containsExactly("BizCertExtractionResponse");
        JsonNode schema = docs.at("/components/schemas/BizCertExtractionResponse");
        assertThat(schema.get("properties").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("bizNo", "storeName", "representativeName", "storeAddress",
                        "openDate", "corpName", "businessType", "businessItem", "ntsVerification");
        // 못 읽은 칸은 null 이 될 수 있고, 국세청 확인 결과만 늘 있다 — 앱이 이 칸에는 null 분기를 두지 않게
        assertThat(schema.get("required")).extracting(JsonNode::asText).containsExactly("ntsVerification");
        assertThat(schema.at("/properties/ntsVerification/enum")).extracting(JsonNode::asText)
                .containsExactly("VERIFIED", "MISMATCH", "UNVERIFIED");
        assertThat(schema.at("/properties/openDate/format").asText()).isEqualTo("date");
        // 아직 나오지 않는 실패 응답도 미리 문서에 적는다 — 앱이 실패 처리를 먼저 만들 수 있게
        assertThat(operation.get("responses").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("200", "400", "429", "503", "501");

        // 전역으로 걸린 토큰 요구를 이 API 에서는 지운다 — 앱이 토큰 없이 부르는 API 로 읽게
        assertThat(operation.get("security").isArray()).isTrue();
        assertThat(operation.get("security")).isEmpty();
        // 501 은 본문이 없다 — 응답 DTO 가 501 의 모양으로 잘못 붙지 않게
        assertThat(operation.at("/responses/501").isObject()).isTrue();
        assertThat(operation.at("/responses/501/content").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("문서가 가리키는 스키마는 전부 실제로 있다")
    void everyReferencedSchemaResolves() throws Exception {
        JsonNode docs = apiDocs();
        JsonNode schemas = docs.at("/components/schemas");

        Set<String> referenced = new HashSet<>();
        collectRefs(docs.at("/paths"), referenced);

        assertThat(referenced).isNotEmpty();
        assertThat(referenced.stream().filter(name -> !schemas.has(name)).toList()).isEmpty();
    }

    private void collectRefs(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String value = ref.asText();
                out.add(value.substring(value.lastIndexOf('/') + 1));
            }
            node.fields().forEachRemaining(e -> collectRefs(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, out));
        }
    }
}
