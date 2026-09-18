package com.ktc4.backend.domain.business.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktc4.backend.domain.business.dto.NtsBusinessStatus;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    private ListAppender<ILoggingEvent> logAppender;
    private Logger ntsApiClientLogger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ntsApiClient = new NtsApiClient(restClient, SERVICE_KEY);

        logAppender = new ListAppender<>();
        logAppender.start();
        ntsApiClientLogger = (Logger) LoggerFactory.getLogger(NtsApiClient.class);
        ntsApiClientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ntsApiClientLogger.detachAppender(logAppender);
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

        // 예외 응답엔 원인이 안 담기므로(ErrorCode 고정 메시지), 서버 로그에 이유가 남는지 확인한다.
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
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
    void 사업자번호가_정확히_100건이면_분할_없이_한_번만_호출한다() {
        // 국세청 API 자체가 1회 최대 100건까지만 받는다(실제 호출로 확인: 101건은 413 거절).
        // 분할 책임은 상위(배치/호출자)로 넘겼으므로, 100건까지는 그대로 한 번에 보낸다.
        List<String> bizNos = bizNoRange(0, 100);

        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(toRequestJson(bizNos)))
                .andRespond(withSuccess(toResponseJson(bizNos), MediaType.APPLICATION_JSON));

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(bizNos);

        assertThat(result).hasSize(100);
        assertThat(result.get(0).bNo()).isEqualTo(bizNos.get(0));
        assertThat(result.get(99).bNo()).isEqualTo(bizNos.get(99));
        mockServer.verify();
    }

    @Test
    void 사업자번호가_100건을_넘으면_API를_호출하지_않고_INVALID_REQUEST_예외를_던진다() {
        // 분할 루프를 지웠으므로 100건 초과는 더 이상 자동으로 나눠 처리하지 않고, 호출자가 나눠서
        // 넘기도록 그 자리에서 막는다. mockServer에 expectation을 등록하지 않았으므로, 만약 코드가
        // 실제로 HTTP 호출을 시도하면 AssertionError가 나서 이 테스트가 실패한다 — 즉 테스트가
        // CustomException으로 끝난다는 것 자체가 "호출을 시도하지 않았다"는 증거다.
        List<String> tooManyBizNos = bizNoRange(0, 101);

        assertThatThrownBy(() -> ntsApiClient.getStatuses(tooManyBizNos))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("101");
                });
    }

    @Test
    void 응답의_request_cnt가_요청_건수와_다르면_경고_로그를_남긴다() {
        // 실제 요청은 2건인데 응답 request_cnt가 1건이라고 답하는, 응답이 덜 온 상황을 재현한다.
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"b_no\":[\"3058148738\",\"1058148739\"]}"))
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

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("3058148738", "1058148739"));

        // 응답이 짧아져도 받은 만큼은 그대로 반환해야 한다 — WARN 로그가 데이터 경로를 끊지 않는지 확인.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).bNo()).isEqualTo("3058148738");
        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    // 인자 순서가 바뀌어도 우연히 통과하지 않도록, 값이 박힌 자리까지 같이 확인한다.
                    assertThat(event.getFormattedMessage()).contains("요청 2건").contains("request_cnt 1건");
                });
    }

    @Test
    void 응답에_request_cnt_필드가_없으면_예외_없이_정상_처리한다() {
        // request_cnt는 nullable(Integer)로 매핑돼 있다 — 필드 자체가 없는 응답에서 언박싱 NPE가
        // 나지 않는지 확인한다.
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"b_no\":[\"3058148738\"]}"))
                .andRespond(withSuccess("""
                        {
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

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("3058148738"));

        assertThat(result).hasSize(1);
        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void 응답에_data_필드가_없으면_경고_로그를_남기고_빈_리스트를_반환한다() {
        // HTTP 는 200으로 성공했지만 바디에 data 필드 자체가 없는 경우(게이트웨이 이상 등) —
        // "조회 대상 0건이라 결과 없음"과 구분되지 않은 채 조용히 빈 리스트만 돌아가면 안 된다.
        mockServer.expect(requestTo(EXPECTED_REQUEST_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "status_code": "OK"
                        }
                        """, MediaType.APPLICATION_JSON));

        List<NtsBusinessStatus> result = ntsApiClient.getStatuses(List.of("3058148738"));

        assertThat(result).isEmpty();
        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void 응답의_request_cnt가_요청_건수와_같으면_경고_로그를_남기지_않는다() {
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

        ntsApiClient.getStatuses(List.of("3058148738"));

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
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
