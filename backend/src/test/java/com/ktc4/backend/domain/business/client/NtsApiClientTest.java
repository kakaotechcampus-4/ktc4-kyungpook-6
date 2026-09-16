package com.ktc4.backend.domain.business.client;

import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link NtsApiClient} 단위 테스트.
 *
 * <p>Spring 컨텍스트 없이 {@code RestClient.builder()}로 직접 만든 빌더에
 * {@link MockRestServiceServer}를 바인딩한 뒤 빌드해서 실제 네트워크 호출 없이 검증한다.
 * {@link BiznoApiClientTest}와 동일하게, Mock 서버를 먼저 바인딩하고 그 다음에 빌드해야
 * 만들어진 {@code RestClient}가 Mock으로 라우팅된다.
 */
class NtsApiClientTest {

    private static final String BASE_URL = "https://api.odcloud.kr/api/nts-businessman/v1/status";
    private static final String SERVICE_KEY = "test-service-key";
    private static final String EXPECTED_REQUEST_URI = BASE_URL + "?serviceKey=" + SERVICE_KEY;

    private MockRestServiceServer mockServer;
    private NtsApiClient ntsApiClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ntsApiClient = new NtsApiClient(restClient, SERVICE_KEY);
    }

    @Test
    void 정상_조회_시_계속사업자_상태를_그대로_반환한다() {
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"b_no\":[\"3058148738\"]}"))
                .andRespond(withSuccess("""
                        {
                          "request_cnt": 1,
                          "match_cnt": 1,
                          "status_code": "OK",
                          "data": [
                            {
                              "b_no": "3058148738",
                              "b_stt": "계속사업자",
                              "b_stt_cd": "01",
                              "tax_type": "부가가치세 일반과세자",
                              "tax_type_cd": "01",
                              "end_dt": "",
                              "utcc_yn": "N",
                              "tax_type_change_dt": "",
                              "invoice_apply_dt": "20120401",
                              "rbf_tax_type": "",
                              "rbf_tax_type_cd": ""
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("3058148738"));

        assertThat(result).hasSize(1);
        NtsBusinessStatus status = result.get(0);
        assertThat(status.bNo()).isEqualTo("3058148738");
        assertThat(status.bStt()).isEqualTo("계속사업자");
        assertThat(status.bSttCd()).isEqualTo("01");
        assertThat(status.taxType()).isEqualTo("부가가치세 일반과세자");
        assertThat(status.invoiceApplyDt()).isEqualTo("20120401");
        mockServer.verify();
    }

    @Test
    void 배치_조회_중_일부만_매칭되면_매칭_안된_항목도_빈_상태값으로_리스트에_남는다() {
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"b_no\":[\"3058148738\",\"0000000000\"]}"))
                .andRespond(withSuccess("""
                        {
                          "request_cnt": 2,
                          "match_cnt": 1,
                          "status_code": "OK",
                          "data": [
                            {
                              "b_no": "3058148738",
                              "b_stt": "계속사업자",
                              "b_stt_cd": "01",
                              "tax_type": "부가가치세 일반과세자",
                              "tax_type_cd": "01",
                              "end_dt": "",
                              "utcc_yn": "N",
                              "tax_type_change_dt": "",
                              "invoice_apply_dt": "20120401",
                              "rbf_tax_type": "",
                              "rbf_tax_type_cd": ""
                            },
                            {
                              "b_no": "0000000000",
                              "b_stt": "",
                              "b_stt_cd": "",
                              "tax_type": "국세청에 등록되지 않은 사업자등록번호이거나 확인할 수 없습니다.",
                              "tax_type_cd": "",
                              "end_dt": "",
                              "utcc_yn": "",
                              "tax_type_change_dt": "",
                              "invoice_apply_dt": "",
                              "rbf_tax_type": "",
                              "rbf_tax_type_cd": ""
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("3058148738", "0000000000"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).bNo()).isEqualTo("3058148738");
        assertThat(result.get(0).bSttCd()).isEqualTo("01");
        assertThat(result.get(1).bNo()).isEqualTo("0000000000");
        assertThat(result.get(1).bSttCd()).isEmpty();
        assertThat(result.get(1).bStt()).isEmpty();
        assertThat(result.get(1).taxType()).contains("확인할 수 없습니다");
    }

    @Test
    void 하이픈_포함_사업자번호를_넘겨도_요청_바디에는_숫자만_담긴다() {
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"b_no\":[\"3058148738\"]}"))
                .andRespond(withSuccess("""
                        {
                          "request_cnt": 1,
                          "match_cnt": 1,
                          "status_code": "OK",
                          "data": [
                            {
                              "b_no": "3058148738",
                              "b_stt": "계속사업자",
                              "b_stt_cd": "01",
                              "tax_type": "",
                              "tax_type_cd": "",
                              "end_dt": "",
                              "utcc_yn": "",
                              "tax_type_change_dt": "",
                              "invoice_apply_dt": "",
                              "rbf_tax_type": "",
                              "rbf_tax_type_cd": ""
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("305-81-48738"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).bNo()).isEqualTo("3058148738");
        mockServer.verify();
    }

    @Test
    void API가_5xx를_반환하면_CustomException을_던진다() {
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> ntsApiClient.getStatuses(List.of("3058148738")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NTS_API_ERROR));
    }

    @Test
    void 응답_바디가_JSON이_아니면_CustomException을_던진다() {
        // 응답이 200 OK + application/json 헤더로 오더라도 실제 바디가 깨진 경우(게이트웨이 장애 시
        // HTML 에러 페이지를 잘못된 헤더로 내려주는 경우 등)를 재현한다.
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("이건 JSON이 아닙니다", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> ntsApiClient.getStatuses(List.of("3058148738")))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NTS_API_ERROR));
    }

    @Test
    void bizNos_안에_null_원소가_있으면_INVALID_REQUEST_예외를_던진다() {
        assertThatThrownBy(() -> ntsApiClient.getStatuses(Arrays.asList("3058148738", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void 빈_리스트를_넘기면_API를_호출하지_않고_빈_리스트를_반환한다() {
        // MockRestServiceServer에 expectation을 하나도 등록하지 않았으므로, getStatuses()가 실제로
        // HTTP 호출을 시도하면 매칭되는 expectation이 없어 그 자리에서 AssertionError가 던져진다.
        // 즉 이 테스트가 예외 없이 끝난다는 것 자체가 "API를 호출하지 않았다"는 증거다.
        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void null_리스트를_넘기면_API를_호출하지_않고_빈_리스트를_반환한다() {
        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(null);

        assertThat(result).isEmpty();
    }

    @Test
    void 사업자번호가_100건을_넘으면_100건씩_나눠서_호출하고_순서대로_이어붙여서_반환한다() {
        // 국세청 API 자체가 1회 최대 100건까지만 받는다(실제 호출로 확인: 101건은 413 거절).
        // 150건을 넘기면 100+50으로 두 번 호출되고, 결과가 입력 순서 그대로 이어붙어야 한다.
        List<String> firstChunk = bizNoRange(0, 100);
        List<String> secondChunk = bizNoRange(100, 50);

        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(toRequestJson(firstChunk)))
                .andRespond(withSuccess(toResponseJson(firstChunk), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(toRequestJson(secondChunk)))
                .andRespond(withSuccess(toResponseJson(secondChunk), MediaType.APPLICATION_JSON));

        List<String> allBizNos = new ArrayList<>(firstChunk);
        allBizNos.addAll(secondChunk);

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(allBizNos);

        assertThat(result).hasSize(150);
        assertThat(result.get(0).bNo()).isEqualTo(firstChunk.get(0));
        assertThat(result.get(99).bNo()).isEqualTo(firstChunk.get(99));
        assertThat(result.get(100).bNo()).isEqualTo(secondChunk.get(0));
        assertThat(result.get(149).bNo()).isEqualTo(secondChunk.get(49));
        mockServer.verify();
    }

    private static List<String> bizNoRange(int start, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> String.valueOf(1_000_000_000L + start + i))
                .toList();
    }

    private static String toRequestJson(List<String> bizNos) {
        return "{\"b_no\":[" + bizNos.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(",")) + "]}";
    }

    private static String toResponseJson(List<String> bizNos) {
        String items = bizNos.stream()
                .map(n -> "{\"b_no\":\"" + n + "\",\"b_stt\":\"계속사업자\",\"b_stt_cd\":\"01\","
                        + "\"tax_type\":\"\",\"tax_type_cd\":\"\",\"end_dt\":\"\",\"utcc_yn\":\"\","
                        + "\"tax_type_change_dt\":\"\",\"invoice_apply_dt\":\"\",\"rbf_tax_type\":\"\","
                        + "\"rbf_tax_type_cd\":\"\"}")
                .collect(Collectors.joining(","));
        return "{\"request_cnt\":" + bizNos.size() + ",\"match_cnt\":" + bizNos.size()
                + ",\"status_code\":\"OK\",\"data\":[" + items + "]}";
    }
}
