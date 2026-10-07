package com.ktc4.backend.domain.job.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 서버가 켜질 때, 그 전에 시작해 끝나지 못한 조사를 실패로 정리한다.
 *
 * <p>실행기 대기열은 메모리에만 있어서 서버가 꺼지면(배포·재시작) 진행 중이던 조사도, 기다리던 조사도 다시는 돌지 않는다.
 * 그대로 두면 영원히 진행 중으로 남아 화면이 끝없이 폴링한다. dev 서버는 머지할 때마다 배포되어 드문 일이 아니다.
 *
 * <p>기준 시각은 이 빈이 만들어질 때 잡는다 — 웹 서버가 뜨기 전이다. 정리는 웹 서버가 요청을 받기 시작한 뒤에 돌기 때문에,
 * "지금 끝나지 않은 조사"를 모두 정리하면 방금 접수된 조사까지 실패로 만들 수 있다.
 */
@Slf4j
@Component
public class UnfinishedJobCleaner implements ApplicationRunner {

    static final String ERROR_RESTARTED = "서버 재시작으로 조사가 중단됐습니다";

    private final JobService jobService;
    private final LocalDateTime bootedAt;

    public UnfinishedJobCleaner(JobService jobService) {
        this.jobService = jobService;
        this.bootedAt = LocalDateTime.now();
    }

    @Override
    public void run(ApplicationArguments args) {
        int cleaned = jobService.failUnfinishedBefore(bootedAt, ERROR_RESTARTED, LocalDateTime.now());
        if (cleaned > 0) {
            log.warn("서버 재시작 전에 끝나지 못한 조사 {}건을 실패로 정리함", cleaned);
        }
    }
}
