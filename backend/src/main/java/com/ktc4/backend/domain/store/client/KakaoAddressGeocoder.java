package com.ktc4.backend.domain.store.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * 카카오 로컬 주소 검색({@code GET /v2/local/search/address.json})으로 도로명 주소를 좌표로 바꾼다.
 *
 * <p>키는 {@code KAKAO_REST_API_KEY} 로 받는다(AI 서버가 장소 검색에 쓰는 키와 같다). 없으면 꺼진다 — 앱은 뜨고 좌표 채우기만
 * 아무것도 하지 않는다. 키는 {@code Authorization: KakaoAK <키>} 헤더로만 싣고 로그에 남기지 않는다. 리다이렉트는 따라가지
 * 않는다 — 키가 실린 헤더가 다른 곳으로 넘어가지 않게.
 *
 * <p>그 주소 탓인 실패(카카오 400, 받은 좌표가 이상함)는 못 찾음으로 돌린다. 묻지 못함으로 돌리면 배치가 기록 없이 멈춰
 * 다음 주기에도 같은 가게에서 멈추고, 그 뒤 가게는 영영 채워지지 않는다.
 *
 * <p>재시도하지 않는다 — 배치가 다음 주기에 다시 묻는다.
 */
@Slf4j
@Component
public class KakaoAddressGeocoder implements AddressGeocoder {

    private static final String PATH = "/v2/local/search/address.json";

    // 아동 앱 지도에 그대로 나가는 값이라 한국 범위(마라도~휴전선 위, 백령도~독도)만 믿는다
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 39.0;
    private static final double MIN_LNG = 124.0;
    private static final double MAX_LNG = 132.0;

    private final RestClient restClient;
    private final boolean enabled;

    @Autowired
    public KakaoAddressGeocoder(
            RestClient.Builder restClientBuilder,
            @Value("${external.kakao.base-url}") String baseUrl,
            @Value("${external.kakao.rest-api-key:}") String apiKey,
            @Value("${external.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${external.read-timeout-ms}") long readTimeoutMs) {
        this(apiKey == null || apiKey.isBlank() ? null : restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey.strip())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMs))
                        .withRedirects(Redirects.DONT_FOLLOW)))
                .build());
        if (!enabled) {
            log.warn("KAKAO_REST_API_KEY 가 없어 가게 좌표 채우기를 끕니다 — 좌표가 없는 가게는 주변 가게에 나오지 않습니다");
        }
    }

    // 테스트에서 가짜 서버에 묶은 RestClient 를 넣는다. null 이면 꺼진 상태다
    KakaoAddressGeocoder(RestClient restClient) {
        this.restClient = restClient;
        this.enabled = restClient != null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public GeocodeResult geocode(String address) {
        if (!enabled) {
            return new GeocodeResult.Failed("키가 없습니다");
        }
        KakaoAddressResponse response;
        try {
            // 주소는 템플릿이 아니라 변수로 넘긴다 — 그래야 &·=·+·{ 까지 인코딩돼 다른 파라미터로 끼어들거나 예외가 나지 않는다
            response = restClient.get()
                    .uri(uri -> uri.path(PATH).queryParam("query", "{query}").queryParam("size", 1).build(address))
                    .retrieve()
                    .body(KakaoAddressResponse.class);
        } catch (HttpStatusCodeException e) {
            // 400 은 주소가 너무 길거나 이상해 거절한 것이다. 키(401·403)·한도(429)·장애(5xx)만 묻지 못함이다
            if (e.getStatusCode().value() == 400) {
                return new GeocodeResult.NotFound();
            }
            return new GeocodeResult.Failed("카카오가 " + e.getStatusCode().value() + " 로 답했습니다");
        } catch (RestClientException e) {
            return new GeocodeResult.Failed("카카오에 닿지 못했습니다: " + e.getClass().getSimpleName());
        }
        if (response == null || response.documents() == null || response.documents().isEmpty()) {
            return new GeocodeResult.NotFound();
        }
        return parse(response.documents().get(0));
    }

    // 카카오는 좌표를 문자열로 준다(x 가 경도, y 가 위도). 숫자가 아니거나 한국 밖이면 믿지 않고 그 주소는 못 찾음으로 둔다
    private static GeocodeResult parse(KakaoAddressResponse.Document document) {
        try {
            double lng = Double.parseDouble(document.x());
            double lat = Double.parseDouble(document.y());
            if (lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG) {
                return new GeocodeResult.Found(lat, lng);
            }
        } catch (NumberFormatException | NullPointerException e) {
            // 아래로 내려가 NotFound
        }
        return new GeocodeResult.NotFound();
    }

    /** 카카오 주소 검색 응답 중 쓰는 칸만. */
    record KakaoAddressResponse(List<Document> documents) {
        record Document(String x, String y) {
        }
    }
}
