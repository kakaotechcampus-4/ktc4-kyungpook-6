package com.ktc4.backend.domain.investigation.client;

import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AI 서버 {@code POST /investigations} 를 가게 한 곳씩 부른다.
 *
 * <p>AI 는 목록을 받지만 한 건짜리 목록으로 보낸다 — 실행기가 가게마다 결과를 저장하고 진행 수를 올리기 때문이다.
 *
 * <p>HTTP 결과를 {@link AiException} 하위 타입으로 번역하는 곳이 여기 하나다. 재시도는 하지 않는다 —
 * 클라이언트가 재시도를 숨기면 호출하는 쪽이 몇 번 시도했는지 모른다(멘토 Q4).
 */
@Component
public class AiApiClient implements AiClient {

    private static final String INVESTIGATIONS_PATH = "/investigations";
    private static final int ERROR_BODY_LIMIT = 500;

    private final RestClient restClient;

    @Autowired
    public AiApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${ai.read-timeout-ms}") long readTimeoutMs) {
        this(restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                .build());
    }

    AiApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    // detect() 대신 JDK HttpClient 를 지정한다 — 타임아웃·연결 실패가 어떤 예외로 오는지가 예외 번역의 전제라,
    // 의존성이 늘어 다른 HTTP 클라이언트가 골라지면 번역이 조용히 어긋난다.
    private static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return ClientHttpRequestFactoryBuilder.jdk().build(settings);
    }

    @Override
    public AiFinding investigate(InvestigationTarget target) {
        AiInvestigationResponse response;
        try {
            response = restClient.post()
                    .uri(INVESTIGATIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(AiInvestigationRequest.from(target)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        throw translateStatus(httpResponse.getStatusCode(), readBodySnippet(httpResponse));
                    })
                    .body(AiInvestigationResponse.class);
        } catch (AiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateIoFailure(e);
        } catch (RestClientException e) {
            // 2xx 인데 본문을 해석하지 못함 (깨진 JSON, 타입이 다른 값)
            throw new AiContractError("AI 응답을 해석할 수 없습니다", e);
        }
        return toFinding(response, target.storeId());
    }

    // 예외 메시지는 로그에만 남는다(화면에는 실행기가 정해 둔 문구를 쓴다). AI 가 왜 거절했는지(FastAPI 의 detail 등)를
    // 알 수 있게 응답 본문 앞부분을 함께 담는다.
    private static AiException translateStatus(HttpStatusCode status, String body) {
        int code = status.value();
        String suffix = " (HTTP " + code + ")" + (body.isEmpty() ? "" : " - 본문: " + body);
        if (code == 503) {
            return new AiUnavailable("AI 조사 구현을 쓸 수 없습니다" + suffix);
        }
        if (code == 429) {
            return new AiQuotaExceeded("AI 사용량 한도를 넘었습니다" + suffix);
        }
        if (status.is5xxServerError()) {
            return new AiTransientError("AI 서버 오류" + suffix);
        }
        return new AiContractError("AI 가 요청을 거절했습니다" + suffix);
    }

    private static String readBodySnippet(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return new String(body.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            // 본문을 못 읽어도 상태코드 번역은 그대로 한다
            return "";
        }
    }

    // HttpConnectTimeoutException 은 HttpTimeoutException 의 하위 타입이라 연결 쪽을 먼저 본다.
    private static AiException translateIoFailure(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new AiTransientError("AI 서버에 연결하지 못했습니다", e);
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new AiReadTimeout("AI 서버가 제한 시간 안에 답하지 않았습니다", e);
            }
        }
        // 그 밖의 I/O 실패(연결이 중간에 끊김 등). 인터럽트도 여기로 오는데, 실행기가 재시도 전에 인터럽트 여부를 먼저 본다.
        return new AiTransientError("AI 서버와 통신하지 못했습니다", e);
    }

    private static AiFinding toFinding(AiInvestigationResponse response, Long storeId) {
        if (response == null || response.results() == null || response.results().size() != 1) {
            throw new AiContractError("AI 응답의 결과 수가 요청 수(1)와 다릅니다");
        }
        AiInvestigationResponse.Result result = response.results().get(0);
        if (result == null) {
            throw new AiContractError("AI 응답의 결과가 비어 있습니다 - storeId=" + storeId);
        }
        if (!storeId.equals(result.storeId())) {
            throw new AiContractError("AI 응답이 다른 가게의 결과입니다 - 요청 storeId=" + storeId
                    + ", 응답 storeId=" + result.storeId());
        }
        if (result.failure() != null) {
            return AiFinding.failed(storeId, result.failure());
        }
        if (result.classification() == null) {
            throw new AiContractError("AI 응답에 실패도 판정도 없습니다 - storeId=" + storeId);
        }
        return AiFinding.success(
                storeId,
                parseEnum(TaskClassification.class, result.classification(), "classification"),
                toProposedChanges(result.proposedChanges()),
                toSignals(result.signals()));
    }

    private static Map<ChangeField, String> toProposedChanges(Map<String, String> proposedChanges) {
        Map<ChangeField, String> changes = new EnumMap<>(ChangeField.class);
        if (proposedChanges == null) {
            return changes;
        }
        proposedChanges.forEach((key, value) -> {
            if (value == null) {
                throw new AiContractError("AI 수정안의 값이 비어 있습니다 - field=" + key);
            }
            changes.put(toField(key), value);
        });
        return changes;
    }

    private static List<AiSignal> toSignals(List<AiInvestigationResponse.SignalItem> signals) {
        List<AiSignal> converted = new ArrayList<>();
        if (signals == null) {
            return converted;
        }
        for (AiInvestigationResponse.SignalItem item : signals) {
            if (item == null) {
                throw new AiContractError("AI 응답의 근거 목록에 빈 항목이 있습니다");
            }
            converted.add(new AiSignal(
                    parseEnum(SignalType.class, item.signalType(), "signalType"),
                    toField(item.field()),
                    item.observed(),
                    item.evidenceText(),
                    item.evidenceUrl()));
        }
        return converted;
    }

    private static ChangeField toField(String key) {
        return ChangeField.fromKey(key)
                .orElseThrow(() -> new AiContractError("AI 응답에 모르는 항목이 있습니다 - field=" + key));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        if (value == null) {
            throw new AiContractError("AI 응답에 " + name + " 가 없습니다");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new AiContractError("AI 응답의 " + name + " 값을 모릅니다 - " + value, e);
        }
    }
}
