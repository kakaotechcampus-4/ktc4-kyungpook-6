package com.ktc4.backend.domain.store.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 카카오 주소 검색 클라이언트 — 요청 모양과 응답 해석을 가짜 서버로 본다. 주소·키는 지어낸 값이다.
 */
@DisplayName("KakaoAddressGeocoder")
class KakaoAddressGeocoderTest {

    private static final String ADDRESS = "가상특별시 예시구 샘플로 123";
    private static final String URL = "https://dapi.kakao.test/v2/local/search/address.json";

    private MockRestServiceServer server;
    private KakaoAddressGeocoder geocoder;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.test")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK test-kakao-key");
        server = MockRestServiceServer.bindTo(builder).build();
        geocoder = new KakaoAddressGeocoder(builder.build());
    }

    @Test
    @DisplayName("주소를 query 로 보내고 첫 결과의 y·x 를 위도·경도로 읽는다")
    void readsFirstDocument() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-kakao-key"))
                .andExpect(queryParam("query", encoded(ADDRESS)))
                .andRespond(withSuccess("""
                        {"documents":[{"address_name":"가상","x":"128.6107","y":"35.8884"},
                                      {"address_name":"다른","x":"1","y":"2"}],"meta":{"total_count":2}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.Found(35.8884, 128.6107));
        server.verify();
    }

    @Test
    @DisplayName("주소의 &·=·+·{ 도 인코딩한다 — 다른 파라미터로 끼어들거나 예외가 나지 않는다")
    void encodesSpecialCharactersInAddress() {
        String address = "가상로 1 & size=30 {q} A+B";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .isEqualTo("query=" + encoded(address) + "&size=1"))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(address)).isEqualTo(new GeocodeResult.NotFound());
        server.verify();
    }

    @Test
    @DisplayName("결과가 없으면 못 찾음 — 주소 탓이라 한동안 다시 묻지 않을 대상")
    void emptyIsNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withSuccess("{\"documents\":[],\"meta\":{\"total_count\":0}}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.NotFound());
    }

    @Test
    @DisplayName("카카오 400 은 못 찾음 — 주소가 이상해 거절한 것이라, 묻지 못함으로 두면 배치가 매번 그 가게에서 멈춘다")
    void badRequestIsNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.NotFound());
    }

    @ParameterizedTest(name = "카카오 {0}")
    @ValueSource(ints = {401, 403, 429, 500, 503})
    @DisplayName("오류로 답하면 묻지 못함 — 주소 탓이 아니라 다음 주기에 다시 묻는다")
    void errorStatusIsFailed(int status) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThat(geocoder.geocode(ADDRESS)).isInstanceOf(GeocodeResult.Failed.class);
    }

    @Test
    @DisplayName("닿지 못하면(시간 초과 등) 묻지 못함")
    void connectionFailureIsFailed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL))).andRespond(withException(new IOException("timeout")));

        assertThat(geocoder.geocode(ADDRESS)).isInstanceOf(GeocodeResult.Failed.class);
    }

    @Test
    @DisplayName("좌표가 숫자가 아니거나 한국 밖이면 믿지 않고 못 찾음 — 배치가 그 가게에서 멈추지 않게")
    void badCoordinatesAreNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withSuccess("{\"documents\":[{\"x\":\"abc\",\"y\":\"35.1\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withSuccess("{\"documents\":[{\"x\":\"128.1\",\"y\":\"95\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(URL)))
                .andRespond(withSuccess("{\"documents\":[{\"x\":\"139.7\",\"y\":\"35.6\"}]}", MediaType.APPLICATION_JSON));

        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.NotFound());
        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.NotFound());
        assertThat(geocoder.geocode(ADDRESS)).isEqualTo(new GeocodeResult.NotFound());
    }

    @Test
    @DisplayName("키가 없으면 꺼지고 아무 데도 묻지 않는다")
    void disabledWithoutKey() {
        KakaoAddressGeocoder off = new KakaoAddressGeocoder(null);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.geocode(ADDRESS)).isInstanceOf(GeocodeResult.Failed.class);
    }

    // 쿼리 값을 엄격하게 인코딩한 모양(공백은 %20)
    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
