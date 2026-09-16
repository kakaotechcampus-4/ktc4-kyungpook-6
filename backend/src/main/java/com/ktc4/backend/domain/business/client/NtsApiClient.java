package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class NtsApiClient implements NtsClient {

    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("\\D");
    // 국세청 API 자체 제한: 1회 호출당 최대 100건 (실제 호출로 확인함 — 100건은 정상 처리,
    // 101건은 HTTP 413 Payload Too Large로 거절됨).
    private static final int MAX_BATCH_SIZE = 100;

    private final RestClient restClient;
    private final String serviceKey;

    @Autowired
    public NtsApiClient(
            RestClient.Builder restClientBuilder,
            @Value("${external.nts.base-url}") String baseUrl,
            @Value("${external.nts.service-key}") String serviceKey,
            @Value("${external.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${external.read-timeout-ms}") long readTimeoutMs) {
        this(
                restClientBuilder
                        .baseUrl(baseUrl)
                        .requestFactory(createRequestFactory(connectTimeoutMs, readTimeoutMs))
                        .build(),
                serviceKey);
    }

    NtsApiClient(RestClient restClient, String serviceKey) {
        this.restClient = restClient;
        this.serviceKey = serviceKey;
    }

    private static ClientHttpRequestFactory createRequestFactory(long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }

    @Override
    public List<NtsBusinessStatus> getStatuses(List<String> bizNos) {
        if (bizNos == null || bizNos.isEmpty()) {
            return List.of();
        }
        if (bizNos.stream().anyMatch(Objects::isNull)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        List<String> normalizedBizNos = bizNos.stream()
                .map(bizNo -> NON_DIGIT_PATTERN.matcher(bizNo).replaceAll(""))
                .toList();

        // 국세청 API가 1회 최대 100건까지만 받으므로, 그보다 많으면 100건씩 나눠서 여러 번 호출하고
        // 순서를 유지한 채 이어붙인다. 호출하는 쪽은 이 분할을 신경 쓸 필요 없다.
        List<NtsBusinessStatus> result = new ArrayList<>();
        for (int i = 0; i < normalizedBizNos.size(); i += MAX_BATCH_SIZE) {
            List<String> chunk = normalizedBizNos.subList(i, Math.min(i + MAX_BATCH_SIZE, normalizedBizNos.size()));
            result.addAll(callNtsApi(chunk, bizNos.size()));
        }
        return result;
    }

    private List<NtsBusinessStatus> callNtsApi(List<String> normalizedChunk, int totalRequestedCount) {
        try {
            NtsStatusResponse response = restClient
                    .post()
                    .uri(uriBuilder -> uriBuilder.queryParam("serviceKey", serviceKey).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NtsStatusRequest(normalizedChunk))
                    .retrieve()
                    .body(NtsStatusResponse.class);

            List<NtsStatusResponse.Item> items = response != null && response.data() != null
                    ? response.data()
                    : List.of();

            return items.stream()
                    .map(NtsApiClient::toBusinessStatus)
                    .toList();
        } catch (RestClientException e) {
            // 요청 URL에 서비스키가 쿼리파라미터로 실리므로, 예외 메시지(URL 포함 가능)는 로그에
            // 남기지 않고 사업자번호 건수 등 비민감 정보만 남긴다.
            log.error("국세청 API 호출 실패 - 전체 요청 {}건 중 이번 청크 {}건, 예외 타입: {}",
                    totalRequestedCount, normalizedChunk.size(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NTS_API_ERROR);
        }
    }

    private static NtsBusinessStatus toBusinessStatus(NtsStatusResponse.Item item) {
        return new NtsBusinessStatus(
                item.bNo(),
                item.bStt(),
                item.bSttCd(),
                item.taxType(),
                item.taxTypeCd(),
                item.endDt(),
                item.utccYn(),
                item.taxTypeChangeDt(),
                item.invoiceApplyDt(),
                item.rbfTaxType(),
                item.rbfTaxTypeCd()
        );
    }
}
