package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.BiznoBusinessCandidate;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class BiznoApiClient implements BiznoClient {

    private static final String QUERY_TYPE = "json";
    private static final String QUERY_STATUS = "N";
    private static final int QUERY_PAGE = 1;

    private final RestClient restClient;
    private final String apiKey;
    private final int pageCount;

    public BiznoApiClient(
            RestClient.Builder builder,
            @Value("${external.bizno.base-url}") String baseUrl,
            @Value("${external.bizno.api-key}") String apiKey,
            @Value("${external.bizno.page-count}") int pageCount,
            @Value("${external.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${external.read-timeout-ms}") long readTimeoutMs) {
        this(
                builder.baseUrl(baseUrl)
                        .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                        .build(),
                apiKey,
                pageCount);
    }

    BiznoApiClient(RestClient restClient, String apiKey, int pageCount) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.pageCount = pageCount;
    }

    private static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }

    @Override
    public List<BiznoBusinessCandidate> search(String keyword) {
        BiznoApiResponse response = callBiznoApi(keyword);

        if (response == null || response.resultCode() != 0) {
            log.error(
                    "비즈노 API 응답 오류 - keyword: {}, resultCode: {}",
                    keyword,
                    response == null ? null : response.resultCode());
            throw new CustomException(ErrorCode.BIZNO_API_ERROR);
        }

        List<BiznoApiResponse.Item> items = response.items();
        if (items == null) {
            return List.of();
        }

        // pagecnt가 실제 매칭 건수보다 크면 비즈노가 남는 자리를 null로 채워서 보낸다(실측 확인됨).
        // 사업자등록번호/법인등록번호 검색처럼 매칭이 소수 건인 경우 흔히 발생하므로 반드시 걸러낸다.
        return items.stream()
                .filter(Objects::nonNull)
                .map(BiznoApiClient::toCandidate)
                .toList();
    }

    private BiznoApiResponse callBiznoApi(String keyword) {
        try {
            return restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("key", apiKey)
                            .queryParam("q", keyword)
                            .queryParam("type", QUERY_TYPE)
                            .queryParam("status", QUERY_STATUS)
                            .queryParam("page", QUERY_PAGE)
                            .queryParam("pagecnt", pageCount)
                            .build())
                    .retrieve()
                    .body(BiznoApiResponse.class);
        } catch (RestClientException e) {
            // 주의: 예외 객체(e)를 그대로 로깅하지 말 것. 요청 URL엔 API 키가 쿼리파라미터로 실려 있고,
            // RestClient가 던지는 예외(원인 체인 포함)가 언제 요청 URL을 메시지에 담을지는 클라이언트
            // 구현/버전에 따라 달라질 수 있다. keyword 등 비민감 정보와 예외 타입 이름만 남겨서
            // 어떤 경우에도 API 키가 로그에 노출되지 않도록 방어적으로 처리한다.
            log.error(
                    "비즈노 API 호출 실패 - keyword: {}, errorType: {}",
                    keyword,
                    e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.BIZNO_API_ERROR);
        }
    }

    private static BiznoBusinessCandidate toCandidate(BiznoApiResponse.Item item) {
        return new BiznoBusinessCandidate(
                item.company(),
                item.bno(),
                item.cno(),
                item.bsttcd(),
                item.bstt(),
                item.taxTypeCd(),
                item.taxtype(),
                item.endDt());
    }
}
