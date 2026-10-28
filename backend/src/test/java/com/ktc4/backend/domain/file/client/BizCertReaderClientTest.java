package com.ktc4.backend.domain.file.client;

import com.ktc4.backend.domain.file.enums.FileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AI 서버 사업자등록증 읽기 클라이언트 — 요청 모양과 응답·실패 해석을 {@link MockRestServiceServer} 로 본다. 값은 지어낸 것이다.
 */
@DisplayName("BizCertReaderClient")
class BizCertReaderClientTest {

    private static final byte[] PDF = "%PDF-1.7 fake".getBytes();

    private MockRestServiceServer server;
    private BizCertReaderClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BizCertReaderClient(builder.build());
    }

    @Test
    @DisplayName("파일을 base64 와 형식으로 실어 보내고, 읽은 값을 그대로 받는다 — 못 읽은 칸은 null")
    void sendsFileAndReadsFields() {
        server.expect(requestTo("http://ai.test/biz-cert-extractions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.contentBase64").value(Base64.getEncoder().encodeToString(PDF)))
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andRespond(withSuccess("""
                        {"bizNo":"1234567890","storeName":"예시분식","representativeName":"홍길동",
                         "storeAddress":null,"openDate":"2019-03-02","corpName":null,
                         "businessType":"음식점업","businessItem":"한식, 분식"}""", MediaType.APPLICATION_JSON));

        AiBizCertReading reading = client.read(PDF, FileType.PDF);

        assertThat(reading.bizNo()).isEqualTo("1234567890");
        assertThat(reading.openDate()).isEqualTo(LocalDate.of(2019, 3, 2));
        assertThat(reading.storeAddress()).isNull();
        server.verify();
    }

    @ParameterizedTest(name = "AI {0}")
    @ValueSource(ints = {401, 413, 415, 422, 500, 502, 503})
    @DisplayName("AI 가 오류로 답하면 BizCertReadFailed — 원인 문장에 응답 본문을 싣지 않는다")
    void errorStatusBecomesReadFailed(int status) {
        server.expect(requestTo("http://ai.test/biz-cert-extractions"))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"홍길동\"}"));

        assertThatThrownBy(() -> client.read(PDF, FileType.PDF))
                .isInstanceOf(BizCertReadFailed.class)
                .hasMessageContaining(String.valueOf(status))
                .hasMessageNotContaining("홍길동");
    }

    @Test
    @DisplayName("AI 에 닿지 못하면(연결 실패·시간 초과) BizCertReadFailed")
    void connectionFailureBecomesReadFailed() {
        server.expect(requestTo("http://ai.test/biz-cert-extractions")).andRespond(withException(new IOException("timeout")));

        assertThatThrownBy(() -> client.read(PDF, FileType.PDF)).isInstanceOf(BizCertReadFailed.class);
    }

    @Test
    @DisplayName("사진은 서버가 가린 형식으로 보낸다")
    void sendsImageMediaType() {
        server.expect(requestTo("http://ai.test/biz-cert-extractions"))
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AiBizCertReading reading = client.read(new byte[]{(byte) 0xFF, (byte) 0xD8}, FileType.JPEG);

        assertThat(reading.bizNo()).isNull();
        server.verify();
    }
}
