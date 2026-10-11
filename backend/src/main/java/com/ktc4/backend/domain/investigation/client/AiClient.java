package com.ktc4.backend.domain.investigation.client;

import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;

/**
 * AI 조사 서버와의 경계. 실행기는 이 인터페이스만 알고, AI 응답 형식은 구현체만 안다.
 *
 * <p>AI 형식이 바뀌면 구현체 하나만 고친다. 테스트에서는 이 인터페이스를 가짜로 바꿔 AI 서버 없이 실행기를 돌린다
 * ({@code NtsClient} / {@code NtsApiClient} 와 같은 나눔).
 */
public interface AiClient {

    /**
     * 가게 한 곳을 AI 에 조사시키고 결과를 받는다. 재시도는 하지 않는다 — 몇 번 시도할지는 호출하는 쪽이 정한다.
     *
     * <p>AI 가 그 가게를 실패로 답한 경우({@code 200} + {@code failure})는 예외가 아니라
     * {@link AiFinding#isFailed()} 인 결과로 돌려준다.
     *
     * @param target 조사할 가게
     * @return 조사 결과 (성공 또는 AI 가 알려 준 실패)
     * @throws AiTransientError 5xx·연결 실패 — 다시 시도할 만한 실패
     * @throws AiReadTimeout    읽기 타임아웃 안에 답이 없음
     * @throws AiContractError  4xx 이거나 응답이 약속과 다름
     * @throws AiQuotaExceeded  429 사용량 한도
     * @throws AiUnavailable    503 조사 구현 없음
     */
    AiFinding investigate(InvestigationTarget target);
}
