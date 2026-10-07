package com.ktc4.backend.domain.job.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("재시작 정리 시점")
class UnfinishedJobCleanerTest {

    @Test
    @DisplayName("기준 시각은 정리가 도는 때가 아니라 빈이 만들어진 때다 — 그 사이 접수된 조사를 실패로 만들지 않게")
    void usesConstructionTimeAsBootTime() {
        JobService jobService = mock(JobService.class);
        LocalDateTime beforeConstruct = LocalDateTime.now();
        UnfinishedJobCleaner cleaner = new UnfinishedJobCleaner(jobService);
        LocalDateTime afterConstruct = LocalDateTime.now();

        cleaner.run(null);

        ArgumentCaptor<LocalDateTime> bootedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobService).failUnfinishedBefore(bootedAt.capture(), eq(UnfinishedJobCleaner.ERROR_RESTARTED), any());
        assertThat(bootedAt.getValue()).isBetween(beforeConstruct, afterConstruct);
    }
}
