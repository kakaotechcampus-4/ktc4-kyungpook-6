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
 * AI 서버만 밀린다. 조사 시작 API 도 끝나지 않은 조사가 있으면 새 조사를 받지 않는다({@code JobService.create}).
 * 실행 대기는 메모리에만 있어 서버가 꺼지면 사라지므로, 다시 켜질 때 남은 조사를 실패로 정리한다({@code UnfinishedJobCleaner}).
 *
 * <p>⚠️ Executor 빈을 하나라도 정의하면 Spring Boot 의 기본 {@code applicationTaskExecutor} 자동 구성이 빠진다.
 * 지금은 그 기본 실행기를 쓰는 곳(MVC 비동기 요청, 이름 없는 {@code @Async})이 없다. 생기면 여기서 따로 정의한다.
 *
 * <p>국세청 재조회는 조사와 <b>같은 실행기를 쓰지 않는다.</b> 조사는 한 건이 수십 분이라, 섞으면
 * 몇 초면 끝날 재조회가 그 뒤에 줄을 서서 "고쳤는데 화면이 그대로" 가 된다 — 즉시 조회를 넣은 이유가 사라진다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** {@code @Async} 에 이름으로 지정한다 — {@code @EnableScheduling} 의 스케줄러도 Executor 라 이름 없이 쓰면 어느 쪽인지 애매하다. */
    public static final String INVESTIGATION_EXECUTOR = "investigationExecutor";

    /** 사업자등록번호를 고친 뒤 그 한 건만 국세청에 다시 묻는 실행기. */
    public static final String NTS_RECHECK_EXECUTOR = "ntsRecheckExecutor";

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

    /**
     * 사업자등록번호 수정 뒤의 국세청 재조회용.
     *
     * <p>스레드 둘에 대기열을 짧게 둔다 — 담당자가 번호를 고치는 일은 드문드문 일어나고, 한 건은 몇 초면 끝난다.
     * 대기열이 차면 {@code CallerRunsPolicy} 로 부른 쪽에서 돌리지 않고 버린다({@code AbortPolicy} 기본값) —
     * 여기서 막으면 커밋이 끝난 요청 스레드를 붙잡게 되고, 못 돌린 건은 새벽 배치가 어차피 채운다.
     *
     * <p>종료 때는 진행 중인 조회를 잠깐 기다린다. 한 건이 길어야 몇 초라 배포를 붙잡지 않는다.
     */
    @Bean(name = NTS_RECHECK_EXECUTOR)
    public ThreadPoolTaskExecutor ntsRecheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("nts-recheck-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
