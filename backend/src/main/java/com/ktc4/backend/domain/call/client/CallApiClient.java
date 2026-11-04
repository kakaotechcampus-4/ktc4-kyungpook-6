package com.ktc4.backend.domain.call.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallQuestion;
import com.ktc4.backend.domain.call.dto.CallQuestionField;
import com.ktc4.backend.domain.call.dto.CallQuestionType;
import com.ktc4.backend.domain.call.dto.CallSnapshot;
import com.ktc4.backend.domain.call.entity.CallAnswer;
import com.ktc4.backend.domain.call.enums.CallStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 전화 API 서버의 {@code POST /v1/verification-call} 과 {@code GET /v1/verification-call/{id}} 를 부른다.
 *
 * <p>재시도는 하지 않는다 — 발신 요청을 우리가 몰래 두 번 보내면 가게에 전화가 두 번 간다. 조회 실패는
 * 폴러가 다음 차례에 다시 읽으므로 여기서 기다릴 이유가 없다.
 *
 * <p><b>오류 응답의 본문은 읽지 않는다.</b> 우리가 보낸 값(가게 전화번호·주소)이 그대로 되돌아올 수 있고,
 * 그러면 로그에 남는다. 전화 API 의 오류 형식은 문서에 없어 어느 칸이 안전한지도 가릴 수 없다 —
 * 그래서 상태코드만 남긴다({@code AiApiClient} 는 FastAPI 의 오류 형식을 알기 때문에 골라 남길 수 있다).
 */
@Component
public class CallApiClient implements CallClient {

    private static final String CALL_PATH = "/v1/verification-call";

    /**
     * 전화 API 가 주는 시각을 우리 시각으로 옮길 때 쓰는 시간대. 앱의 다른 시각은 모두
     * {@code LocalDateTime.now()} 로 적히므로 그것과 같은 시간대여야 나란히 비교된다.
     */
    private static final ZoneId CALL_ZONE = ZoneId.systemDefault();

    /** 주소나 키가 없으면 null 이다 — 그때는 부를 때마다 {@link CallNotConfigured} 로 거절한다. */
    private final RestClient restClient;

    @Autowired
    public CallApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${call.base-url:}") String baseUrl,
            @Value("${call.api-key:}") String apiKey,
            @Value("${call.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${call.read-timeout-ms}") long readTimeoutMs) {
        this(isBlank(baseUrl) || isBlank(apiKey) ? null : restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                .build());
    }

    CallApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // detect() 대신 JDK HttpClient 를 지정한다 — 타임아웃·연결 실패가 어떤 예외로 오는지가 예외 번역의 전제라,
    // 의존성이 늘어 다른 HTTP 클라이언트가 골라지면 번역이 조용히 어긋난다(AiApiClient 와 같은 이유).
    private static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return ClientHttpRequestFactoryBuilder.jdk().build(settings);
    }

    @Override
    public boolean isConfigured() {
        return restClient != null;
    }

    @Override
    public String request(CallOrder order) {
        CreateResponse response = call(() -> client().post()
                .uri(CALL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(CreateRequest.from(order))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw translateStatus(res.getStatusCode(), "전화 요청");
                })
                .body(CreateResponse.class));
        if (response == null || isBlank(response.verificationId())) {
            throw new CallContractError("전화 API 응답에 verificationId 가 없습니다");
        }
        return response.verificationId();
    }

    @Override
    public CallSnapshot read(String externalId) {
        StatusResponse response = call(() -> client().get()
                .uri(CALL_PATH + "/{verificationId}", externalId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw translateStatus(res.getStatusCode(), "전화 상태 조회");
                })
                .body(StatusResponse.class));
        if (response == null) {
            throw new CallContractError("전화 API 가 상태를 보내지 않았습니다");
        }
        return toSnapshot(response);
    }

    private RestClient client() {
        if (restClient == null) {
            throw new CallNotConfigured("전화 API 주소(call.base-url)나 키(call.api-key)가 없습니다");
        }
        return restClient;
    }

    // 두 호출의 예외 번역이 같아서 한 군데로 모은다.
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (CallException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateIoFailure(e);
        } catch (RestClientException e) {
            // 2xx 인데 본문을 해석하지 못함 (깨진 JSON, 타입이 다른 값)
            throw new CallContractError("전화 API 응답을 해석할 수 없습니다", e);
        }
    }

    private static CallException translateStatus(HttpStatusCode status, String what) {
        String suffix = what + " 실패 (HTTP " + status.value() + ")";
        if (status.is5xxServerError()) {
            return new CallTransientError("전화 API 서버 오류 - " + suffix);
        }
        return new CallContractError("전화 API 가 요청을 거절했습니다 - " + suffix);
    }

    // HttpConnectTimeoutException 은 HttpTimeoutException 의 하위 타입이라 연결 쪽을 먼저 본다.
    private static CallException translateIoFailure(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                return new CallTransientError("전화 API 에 연결하지 못했습니다", e);
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new CallTransientError("전화 API 가 제한 시간 안에 답하지 않았습니다", e);
            }
        }
        return new CallTransientError("전화 API 와 통신하지 못했습니다", e);
    }

    private static CallSnapshot toSnapshot(StatusResponse response) {
        return new CallSnapshot(
                parseStatus(response.status()),
                toAnswers(response.answers()),
                toLocal(response.startedAt()),
                toLocal(response.connectedAt()),
                toLocal(response.endedAt()),
                response.failureReason());
    }

    private static CallStatus parseStatus(String status) {
        if (status == null) {
            throw new CallContractError("전화 API 응답에 status 가 없습니다");
        }
        try {
            return CallStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new CallContractError("전화 API 의 status 값을 모릅니다 - " + status, e);
        }
    }

    private static List<CallAnswer> toAnswers(List<StatusResponse.Answer> answers) {
        List<CallAnswer> converted = new ArrayList<>();
        if (answers == null) {
            return converted;
        }
        for (StatusResponse.Answer answer : answers) {
            if (answer == null) {
                throw new CallContractError("전화 API 답 목록에 빈 항목이 있습니다");
            }
            /*
              모르는 항목 키가 와도 버리지 않고 그대로 담는다 — 우리가 묻지 않은 것을 전화 API 가
              답했다면 그 자체가 담당자와 심준성님이 봐야 할 정보다. 화면에 라벨을 붙일 때
              CallField 로 찾지 못한 키는 키 그대로 보여 준다.
            */
            converted.add(new CallAnswer(answer.questionId(), answer.field(), stringify(answer.value())));
        }
        return converted;
    }

    // 값은 문자열이나 JSON 불리언으로 온다(전화 API 문서). 숫자 등 다른 타입이 와도 글자로 남긴다 — 버리지 않는다.
    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime toLocal(OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(CALL_ZONE).toLocalDateTime();
    }

    /**
     * {@code POST /v1/verification-call} 요청 본문. 칸 이름이 곧 전화 API 와의 약속이다.
     *
     * <p>{@code null} 인 칸은 보내지 않는다 — 두 질문 방식이 쓰는 칸이 달라, 안 쓰는 칸을 {@code null} 로
     * 실어 보내면 전화 API 가 "빈 값을 보냈다" 로 읽을 수 있다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateRequest(Target target, List<Question> questions) {

        record Target(String storeLocation, String storeName, String phoneNumber) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Question(String id, String type, List<Field> fields,
                        String field, String naturalField, Object currentValue, Object expectedValue) {
        }

        record Field(String field, String naturalField, Object currentValue) {
        }

        static CreateRequest from(CallOrder order) {
            return new CreateRequest(
                    new Target(order.storeLocation(), order.storeName(), order.phoneNumber()),
                    order.questions().stream().map(CreateRequest::toQuestion).toList());
        }

        private static Question toQuestion(CallQuestion question) {
            if (question.type() == CallQuestionType.CHANGE_DETECTION) {
                List<Field> fields = question.fields().stream()
                        .map(item -> new Field(item.field().apiField(), item.field().naturalField(), value(item)))
                        .toList();
                return new Question(question.id(), question.type().name(), fields, null, null, null, null);
            }
            CallQuestionField item = question.fields().get(0);
            return new Question(question.id(), question.type().name(), null,
                    item.field().apiField(), item.field().naturalField(),
                    value(item), typed(item.field().isBoolean(), question.expectedValue()));
        }

        private static Object value(CallQuestionField item) {
            return typed(item.field().isBoolean(), item.currentValue());
        }

        // 참·거짓으로 묻는 항목은 JSON 불리언으로 보낸다. 한 질문 안에서 현재 값과 확인할 값의 타입이 같아야 한다(전화 API 문서).
        private static Object typed(boolean asBoolean, String value) {
            if (value == null) {
                return null;
            }
            return asBoolean ? Boolean.valueOf(value) : value;
        }
    }

    /** {@code POST /v1/verification-call} 응답 ({@code 202 Accepted}). */
    record CreateResponse(String verificationId) {
    }

    /**
     * {@code GET /v1/verification-call/{id}} 응답.
     *
     * <p>{@code status} 를 문자열로 받는다 — 모르는 값을 Jackson 이 아니라 우리가 걸러
     * {@link CallContractError} 로 바꾸기 위해서다. {@code createdAt} 은 우리가 요청을 넣은 시각을
     * 이미 갖고 있어 받지 않는다.
     */
    record StatusResponse(String verificationId, String status, List<Answer> answers,
                          OffsetDateTime startedAt, OffsetDateTime connectedAt, OffsetDateTime endedAt,
                          String failureReason) {

        record Answer(String questionId, String field, Object value) {
        }
    }
}
