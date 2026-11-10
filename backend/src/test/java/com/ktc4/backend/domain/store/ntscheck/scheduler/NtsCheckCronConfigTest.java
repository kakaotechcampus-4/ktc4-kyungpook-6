package com.ktc4.backend.domain.store.ntscheck.scheduler;

import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 국세청 배치 주기 설정({@code nts-check.cron})이 실제 스케줄 등록까지 반영되는지 확인한다.
 *
 * <p>{@code @Scheduled} 의 값은 애너테이션 문자열이라 단위 테스트로는 "무엇이 등록됐는가" 를 볼 수
 * 없다. 설정 이름을 잘못 적거나 기본값을 빠뜨리면 컴파일도 테스트도 통과하면서 배치만 조용히
 * 안 돌거나(또는 끈 줄 알았는데 계속 돌거나) 하므로, 등록된 작업을 직접 읽어 고정한다.
 *
 * <p>크론으로 도는 배치는 이것 하나뿐이다 — 나머지 배치(업로드 정리·좌표 채우기·전화 상태 조회)는
 * {@code fixedDelayString} 을 쓴다. 그래서 등록된 크론 작업 목록 전체를 그대로 비교한다.
 */
@SpringBootTest
@DisplayName("국세청 배치 주기 설정 — 기본값")
class NtsCheckCronConfigTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    @DisplayName("설정을 주지 않으면 새벽 3시 크론으로 등록된다")
    void registersDefaultCron() {
        assertThat(cronExpressions(scheduledTaskHolder)).containsExactly("0 0 3 * * *");
    }

    /** 등록된 크론 작업의 표현식 목록. */
    static List<String> cronExpressions(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .map(CronTask::getExpression)
                .toList();
    }
}

/**
 * {@code nts-check.cron=-} 로 배치를 끌 수 있는지 확인한다.
 *
 * <p>이 값({@link org.springframework.scheduling.annotation.Scheduled#CRON_DISABLED})은 시드로
 * 넣은 국세청 확인 기록을 배치가 덮어쓰면 안 되는 기간에 쓴다. "껐다" 가 실제로 등록 안 됨을
 * 뜻하는지는 눈으로 확인할 수 없어 여기서 고정한다.
 */
@SpringBootTest(properties = "nts-check.cron=-")
@DisplayName("국세청 배치 주기 설정 — 끄기")
class NtsCheckCronDisabledTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    @DisplayName("'-' 를 주면 배치가 아예 등록되지 않는다")
    void doesNotRegisterWhenDisabled() {
        assertThat(NtsCheckCronConfigTest.cronExpressions(scheduledTaskHolder)).isEmpty();
    }
}
