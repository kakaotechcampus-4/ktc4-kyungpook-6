package com.ktc4.backend.domain.file.client;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.global.security.ApiKeyAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Base64;

/**
 * AI 서버의 사업자등록증 읽기({@code POST /biz-cert-extractions})를 부른다. 조사 클라이언트({@code AiApiClient})와
 * 같은 서버·같은 {@code X-API-KEY} 를 쓰지만 기다리는 시간이 다르다 — 조사는 한 곳에 1분 넘게 걸리고, 읽기는 점주가
 * 가입 화면에서 기다리는 동안이라 짧게 둔다.
 *
 * <p>실패를 하나로 묶는다({@link BizCertReadFailed}). 시간 초과·연결 실패·AI 의 4xx·5xx 어느 쪽이든 점주에게는
 * "직접 입력" 으로 넘기는 같은 결과다. 재시도하지 않는다 — 기다리는 사람이 있고, 부를 때마다 모델 비용이 든다.
 */
@Component
public class BizCertReaderClient {

    private static final String PATH = "/biz-cert-extractions";

    private final RestClient restClient;

    @Autowired
    public BizCertReaderClient(
            RestClient.Builder restClientBuilder,
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${ai.biz-cert-read-timeout-ms:25000}") long readTimeoutMs,
            @Value("${ai.api-key:}") String apiKey) {
        this(withApiKey(restClientBuilder, apiKey)
                .baseUrl(baseUrl)
                .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                .build());
    }

    BizCertReaderClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient.Builder withApiKey(RestClient.Builder builder, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return builder;
        }
        return builder.defaultHeader(ApiKeyAuthenticationFilter.HEADER, apiKey);
    }

    // AiApiClient 와 같은 이유로 JDK HttpClient 를 지정한다 — 다른 클라이언트가 골라지면 타임아웃이 다르게 걸린다
    private static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return ClientHttpRequestFactoryBuilder.jdk().build(settings);
    }

    /**
     * 사업자등록증 파일을 읽어 달라고 한다.
     *
     * @param content 파일 내용
     * @param type    서버가 저장할 때 내용으로 가린 형식
     * @return 읽은 값. 못 읽은 칸은 null
     * @throws BizCertReadFailed 어떤 이유로든 읽지 못했을 때
     */
    public AiBizCertReading read(byte[] content, FileType type) {
        AiBizCertReading reading;
        try {
            reading = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiBizCertRequest(Base64.getEncoder().encodeToString(content), type.mediaType()))
                    .retrieve()
                    .body(AiBizCertReading.class);
        } catch (HttpStatusCodeException e) {
            // 본문은 싣지 않는다 — AI 는 원인 문장을 고정해 보내지만, 읽은 값이 섞일 여지를 두지 않는다
            throw new BizCertReadFailed("AI 서버가 " + e.getStatusCode().value() + " 로 답했습니다", e);
        } catch (RestClientException e) {
            throw new BizCertReadFailed("AI 서버에 닿지 못했습니다: " + e.getClass().getSimpleName(), e);
        }
        if (reading == null) {
            throw new BizCertReadFailed("AI 서버가 빈 본문으로 답했습니다", null);
        }
        return reading;
    }

    /** AI 서버에 보내는 모양 — {@code ai/src/biz_cert/models.py} 의 {@code BizCertExtractionRequest} 와 같다. */
    record AiBizCertRequest(String contentBase64, String mimeType) {
    }
}
