package com.ktc4.backend.support;

import com.ktc4.backend.domain.call.client.CallClient;
import com.ktc4.backend.domain.call.client.CallNotConfigured;
import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallSnapshot;
import com.ktc4.backend.domain.call.repository.VerificationCallRepository;
import com.ktc4.backend.domain.call.service.CallService;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 조사 쪽 DB 테스트에 확인 전화 서비스를 끼운다 — <b>전화 API 는 없는 환경으로</b>.
 *
 * <p>조사 결과 조회({@code JobQueryService})가 카드마다 전화 기록을 붙이므로 {@link CallService}
 * 빈이 필요하다. 전화 API 를 실제로 부르는 부분은 {@code CallApiClientTest} 가, 걸지 말지를 정하는
 * 규칙은 {@code CallServiceTest} 가 따로 본다 — 여기서는 전화 기록이 DB 에서 어떻게 읽히는지만 쓴다.
 *
 * <p>전화 API 를 가짜 HTTP 서버로 두지 않고 "주소·키가 없는 환경" 으로 둔 이유는, DB 테스트가 실수로
 * 전화를 거는 길을 아예 없애려는 것이다. 걸려고 하면 {@link CallNotConfigured} 로 바로 막힌다.
 */
@TestConfiguration
public class CallWithoutApiConfig {

    @Bean
    public CallClient callClient() {
        return new CallClient() {

            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public String request(CallOrder order) {
                throw new CallNotConfigured("테스트에서는 전화를 걸지 않습니다");
            }

            @Override
            public CallSnapshot read(String externalId) {
                throw new CallNotConfigured("테스트에서는 전화 API 를 부르지 않습니다");
            }
        };
    }

    @Bean
    public CallService callService(VerificationCallRepository callRepository,
                                   CallClient callClient,
                                   TaskService taskService,
                                   VerificationService verificationService) {
        return new CallService(callRepository, callClient, taskService, verificationService, 15);
    }
}
