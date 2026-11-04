package com.ktc4.backend.domain.call.client;

import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallSnapshot;

/**
 * 전화 API 서버와의 경계. 서비스는 이 인터페이스만 알고, 전화 API 의 요청·응답 형식은 구현체만 안다.
 *
 * <p>전화 API 형식이 바뀌면 구현체 하나만 고친다. 테스트에서는 이것을 가짜로 바꿔 전화 API 없이 서비스를
 * 돌린다({@code AiClient} / {@code AiApiClient} 와 같은 나눔).
 *
 * <p><b>대기열은 전화 API 가 갖고 있다.</b> 우리는 요청을 넣는 즉시 {@code verificationId} 를 받고,
 * 실제 발신 순서와 동시성은 전화 API 가 정한다 — 그래서 여기에 우리 대기열이 없다.
 */
public interface CallClient {

    /**
     * 전화 기능을 쓸 수 있는가 — 전화 API 주소와 키가 둘 다 들어와 있는가.
     *
     * <p>폴러가 헛돌지 않도록 먼저 본다. 값이 없는 환경에서는 걸어 둔 전화도 없어서 읽을 것도 없다.
     *
     * @return 부를 수 있으면 true
     */
    boolean isConfigured();

    /**
     * 전화 한 통을 요청한다. 통화가 끝나기를 기다리지 않고 바로 돌아온다.
     *
     * @param order 어디에 걸어 무엇을 물을지
     * @return 전화 API 가 붙인 {@code verificationId}
     * @throws CallNotConfigured   전화 API 주소·키가 없음
     * @throws CallTransientError  5xx·연결 실패·타임아웃
     * @throws CallContractError   4xx 이거나 응답이 약속과 다름
     */
    String request(CallOrder order);

    /**
     * 요청한 전화의 지금 상태와 확인 결과를 읽는다.
     *
     * @param externalId 전화 API 의 {@code verificationId}
     * @return 지금 상태
     * @throws CallNotConfigured   전화 API 주소·키가 없음
     * @throws CallTransientError  5xx·연결 실패·타임아웃
     * @throws CallContractError   4xx(없는 id 포함)이거나 응답이 약속과 다름
     */
    CallSnapshot read(String externalId);
}
