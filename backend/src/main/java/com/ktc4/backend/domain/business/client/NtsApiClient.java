package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import java.time.Duration;
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
            // CustomException은 ErrorCode의 고정 메시지만 담아 호출 원인이 응답에 안 남으므로,
            // 서버 로그에라도 무엇이 문제였는지 남긴다.
            log.warn("국세청 조회 요청에 null 원소가 포함되어 거부함 - 요청 {}건", bizNos.size());
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        // 분할 책임은 호출자(배치 등)에게 맡긴다 — 여기서 조용히 나눠 보내면 호출자가 "한 번에
        // 몇 건까지 되는지"를 모른 채 계속 100건 넘게 넘길 수 있어, 그 자리에서 바로 막는다.
        if (bizNos.size() > MAX_BATCH_SIZE) {
            log.warn("국세청 조회 요청 건수가 최대치를 넘어 거부함 - 요청 {}건, 최대 {}건",
                    bizNos.size(), MAX_BATCH_SIZE);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        List<String> normalizedBizNos = bizNos.stream()
                .map(bizNo -> NON_DIGIT_PATTERN.matcher(bizNo).replaceAll(""))
                .toList();

        return callNtsApi(normalizedBizNos);
    }

    private List<NtsBusinessStatus> callNtsApi(List<String> normalizedBizNos) {
        try {
            NtsStatusResponse response = restClient
                    .post()
                    .uri(uriBuilder -> uriBuilder.queryParam("serviceKey", serviceKey).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NtsStatusRequest(normalizedBizNos))
                    .retrieve()
                    .body(NtsStatusResponse.class);

            if (response == null || response.data() == null) {
                // HTTP 자체는 성공(2xx)했지만 바디가 비어 있거나 data 필드가 없는 경우 — 조회 대상이
                // 0건이라 결과가 없는 정상 케이스와 겉모습이 똑같아지므로, 로그 없이 빈 리스트만
                // 반환하면 실제로는 응답을 못 받은 상황을 알아챌 방법이 없어진다.
                log.warn("국세청 응답이 비어 있음 - 요청 {}건", normalizedBizNos.size());
                return List.of();
            }

            // 요청 건수와 응답 request_cnt가 다르면 응답이 덜 온 것 — 호출은 성공했으니 ERROR가
            // 아니라 WARN으로, 나중에 추적할 수 있게 건수만 남긴다. 이 경우 반환 리스트가 요청보다
            // 짧아질 수 있으므로, 호출하는 쪽은 결과를 리스트 위치가 아니라 bNo 값으로 찾아야 한다
            // (NtsClient 인터페이스 계약 참고).
            if (response.requestCnt() != null && response.requestCnt() != normalizedBizNos.size()) {
                log.warn("국세청 응답 건수 불일치 - 요청 {}건, 응답 request_cnt {}건",
                        normalizedBizNos.size(), response.requestCnt());
            }

            return response.data().stream()
                    .map(NtsApiClient::toBusinessStatus)
                    .toList();
        } catch (RestClientException e) {
            // 요청 URL에 서비스키가 쿼리파라미터로 실리므로, 예외 메시지(URL 포함 가능)는 로그에
            // 남기지 않고 사업자번호 건수 등 비민감 정보만 남긴다.
            log.error("국세청 API 호출 실패 - 요청 {}건, 예외 타입: {}",
                    normalizedBizNos.size(), e.getClass().getSimpleName());
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
