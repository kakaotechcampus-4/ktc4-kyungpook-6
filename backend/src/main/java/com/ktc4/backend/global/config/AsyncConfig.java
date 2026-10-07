package com.ktc4.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 관리자 조사(Job) 실행기용 비동기 설정.
 *
 * <p>{@code @EnableAsync} 를 {@code BackendApplication} 이 아니라 여기 둔다 — {@link SchedulingConfig} 와 같은 이유로
 * 슬라이스 테스트가 이 설정을 끌어오지 않게 분리한다.
 *
 * <p>실행기 스레드는 하나다. AI 가 가게마다 수십 초를 쓰고 사용량 한도도 있어, 조사를 동시에 돌려도 빨라지지 않고
 * AI 서버만 밀린다. 나중에 들어온 조사는 대기열에서 앞 조사가 끝나기를 기다린다({@code Job.status = PENDING}).
 * 대기열은 메모리에만 있어 서버가 꺼지면 사라지므로, 다시 켜질 때 남은 조사를 실패로 정리한다({@code UnfinishedJobCleaner}).
 *
 * <p>⚠️ Executor 빈을 하나라도 정의하면 Spring Boot 의 기본 {@code applicationTaskExecutor} 자동 구성이 빠진다.
 * 지금은 그 기본 실행기를 쓰는 곳(MVC 비동기 요청, 이름 없는 {@code @Async})이 없다. 생기면 여기서 따로 정의한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** {@code @Async} 에 이름으로 지정한다 — {@code @EnableScheduling} 의 스케줄러도 Executor 라 이름 없이 쓰면 어느 쪽인지 애매하다. */
    public static final String INVESTIGATION_EXECUTOR = "investigationExecutor";

    @Bean(name = INVESTIGATION_EXECUTOR)
    public ThreadPoolTaskExecutor investigationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("investigation-");
        // 종료 때 진행 중인 조사를 기다리지 않는다 — 한 건이 수십 분이라 배포가 멈춘다. 끊긴 조사는 재시작 정리가 실패로 남긴다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
