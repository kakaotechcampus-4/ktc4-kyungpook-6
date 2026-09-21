package com.ktc4.backend.domain.business.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktc4.backend.domain.business.dto.BiznoBusinessCandidate;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/**
 * {@link BiznoApiClient}의 순수 단위 테스트.
 * Spring 컨텍스트 없이 {@link RestClient#builder()}와 {@link MockRestServiceServer}만으로 구성한다.
 */
class BiznoApiClientTest {

    private static final String BASE_URL = "https://bizno.net/api/fapi";
    private static final String API_KEY = "test-api-key";
    private static final int PAGE_COUNT = 20;

    private MockRestServiceServer mockServer;
    private BiznoApiClient biznoApiClient;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger biznoApiClientLogger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        biznoApiClient = new BiznoApiClient(restClient, API_KEY, PAGE_COUNT);

        logAppender = new ListAppender<>();
        logAppender.start();
        biznoApiClientLogger = (Logger) LoggerFactory.getLogger(BiznoApiClient.class);
        biznoApiClientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        biznoApiClientLogger.detachAppender(logAppender);
    }

    @Test
    void 상호명으로_검색하면_단건_후보를_반환한다() {
        String responseBody =
                """
                {
                  "resultCode": 0,
                  "resultMsg": "OK",
                  "page": "1",
                  "maxpage": 1,
                  "pagecnt": "20",
                  "totalCount": 1,
                  "items": [
                    {
                      "company": "성심당",
                      "bno": "305-81-48738",
                      "cno": "",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "부가가치세 일반과세자",
                      "EndDt": ""
                    }
                  ]
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<BiznoBusinessCandidate> result = biznoApiClient.search("성심당");

        assertThat(result).hasSize(1);
        BiznoBusinessCandidate candidate = result.get(0);
        assertThat(candidate.company()).isEqualTo("성심당");
        assertThat(candidate.bno()).isEqualTo("305-81-48738");
        assertThat(candidate.cno()).isEmpty();
        assertThat(candidate.bsttcd()).isEqualTo("01");
        assertThat(candidate.bstt()).isEqualTo("계속사업자");
        assertThat(candidate.taxTypeCd()).isEmpty();
        assertThat(candidate.taxtype()).isEqualTo("부가가치세 일반과세자");
        assertThat(candidate.endDt()).isEmpty();
        mockServer.verify();
    }

    @Test
    void 상호명으로_검색하면_여러건_후보를_반환한다() {
        String responseBody =
                """
                {
                  "resultCode": 0,
                  "resultMsg": "OK",
                  "page": "1",
                  "maxpage": 1,
                  "pagecnt": "20",
                  "totalCount": 2,
                  "items": [
                    {
                      "company": "성심당 본점",
                      "bno": "305-81-48738",
                      "cno": "",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "부가가치세 일반과세자",
                      "EndDt": ""
                    },
                    {
                      "company": "성심당 롯데백화점점",
                      "bno": "305-81-99999",
                      "cno": "",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "부가가치세 일반과세자",
                      "EndDt": ""
                    }
                  ]
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<BiznoBusinessCandidate> result = biznoApiClient.search("성심당");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(BiznoBusinessCandidate::company)
                .containsExactly("성심당 본점", "성심당 롯데백화점점");
        mockServer.verify();
    }

    @Test
    void 무관한_결과가_채워져도_필터링_없이_그대로_반환한다() {
        String responseBody =
                """
                {
                  "resultCode": 0,
                  "resultMsg": "OK",
                  "page": "1",
                  "maxpage": 1,
                  "pagecnt": "20",
                  "totalCount": 2,
                  "items": [
                    {
                      "company": "성심병원",
                      "bno": "123-45-67890",
                      "cno": "",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "면세사업자",
                      "EndDt": ""
                    },
                    {
                      "company": "성심의원",
                      "bno": "111-22-33333",
                      "cno": "",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "면세사업자",
                      "EndDt": ""
                    }
                  ]
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<BiznoBusinessCandidate> result = biznoApiClient.search("성심당");

        // "성심당"과 무관한 병원/의원 결과가 채워져 왔지만, 필터링 없이 그대로 2건 다 반환해야 한다.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(BiznoBusinessCandidate::company)
                .containsExactly("성심병원", "성심의원");
        mockServer.verify();
    }

    @Test
    void TaxTypeCd와_EndDt_필드가_정확히_매핑된다() {
        String responseBody =
                """
                {
                  "resultCode": 0,
                  "resultMsg": "OK",
                  "page": "1",
                  "maxpage": 1,
                  "pagecnt": "20",
                  "totalCount": 1,
                  "items": [
                    {
                      "company": "폐업상회",
                      "bno": "999-88-77777",
                      "cno": "",
                      "bsttcd": "02",
                      "bstt": "폐업자",
                      "TaxTypeCd": "02",
                      "taxtype": "폐업자",
                      "EndDt": "20200101"
                    }
                  ]
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<BiznoBusinessCandidate> result = biznoApiClient.search("폐업상회");

        assertThat(result).hasSize(1);
        BiznoBusinessCandidate candidate = result.get(0);
        assertThat(candidate.taxTypeCd()).isEqualTo("02");
        assertThat(candidate.endDt()).isEqualTo("20200101");
        mockServer.verify();
    }

    @Test
    void pagecnt보다_실제_매칭_건수가_적어서_items에_null이_섞여와도_필터링하고_반환한다() {
        // 실제 API 키로 사업자등록번호 검색 시 확인됨: totalCount=1인데 pagecnt=3을 요청하면
        // 비즈노가 남는 자리를 null로 채워서 보낸다. 이 상황을 그대로 재현한다.
        String responseBody =
                """
                {
                  "resultCode": 0,
                  "resultMsg": "NORMAL SERVICE.",
                  "page": "1",
                  "maxpage": 1,
                  "pagecnt": "20",
                  "totalCount": 1,
                  "items": [
                    {
                      "company": "로쏘 주식회사",
                      "bno": "305-81-48738",
                      "cno": "160111-0123408",
                      "bsttcd": "01",
                      "bstt": "계속사업자",
                      "TaxTypeCd": "",
                      "taxtype": "부가가치세 일반과세자",
                      "EndDt": ""
                    },
                    null,
                    null
                  ]
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<BiznoBusinessCandidate> result = biznoApiClient.search("3058148738");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).company()).isEqualTo("로쏘 주식회사");
        mockServer.verify();
    }

    @Test
    void API_서버_오류_응답이면_BIZNO_API_ERROR_예외를_던진다() {
        expectSearchRequest().andRespond(withServerError());

        assertThatThrownBy(() -> biznoApiClient.search("성심당"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZNO_API_ERROR));
        mockServer.verify();
    }

    @Test
    void 응답_바디가_JSON이_아니면_BIZNO_API_ERROR_예외를_던진다() {
        expectSearchRequest().andRespond(withSuccess("이건 JSON이 아닙니다", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> biznoApiClient.search("성심당"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZNO_API_ERROR));
        mockServer.verify();
    }

    @Test
    void resultCode가_0이_아니면_BIZNO_API_ERROR_예외를_던진다() {
        String responseBody =
                """
                {
                  "resultCode": 1,
                  "resultMsg": "요청 오류",
                  "page": "1",
                  "maxpage": 0,
                  "pagecnt": "20",
                  "totalCount": 0,
                  "items": []
                }
                """;
        expectSearchRequest().andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> biznoApiClient.search("성심당"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZNO_API_ERROR));
        mockServer.verify();
    }

    @Test
    void 연결_실패_시_BIZNO_API_ERROR_예외를_던지고_로그에_API_키를_남기지_않는다() {
        // MockRestServiceServer의 ResponseCreator에서 IOException을 던지면 RestClient가 이를
        // ResourceAccessException으로 감싼다. API 키는 요청 URL의 쿼리파라미터에 실려 있으므로,
        // 이 예외(또는 원인 체인)를 그대로 로깅하면 키가 노출될 위험이 있다 — 로그 출력 자체에
        // API 키 문자열이 등장하지 않는지 검증한다.
        expectSearchRequest().andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThatThrownBy(() -> biznoApiClient.search("성심당"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZNO_API_ERROR));

        assertThat(logAppender.list).isNotEmpty();
        for (ILoggingEvent event : logAppender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(API_KEY);
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain(API_KEY);
            }
        }
        mockServer.verify();
    }

    private ResponseActions expectSearchRequest() {
        return mockServer.expect(requestTo(startsWith(BASE_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("key", API_KEY))
                .andExpect(queryParam("type", "json"))
                .andExpect(queryParam("status", "N"))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParam("pagecnt", "20"));
    }
}
