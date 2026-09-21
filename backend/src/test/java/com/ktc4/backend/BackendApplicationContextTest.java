package com.ktc4.backend;

import com.ktc4.backend.domain.store.ntscheck.scheduler.NtsCheckScheduler;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링 컨텍스트가 실제로 기동되는지 확인하는 스모크 테스트.
 *
 * <p>단위 테스트는 {@code new XxxClient(...)} 처럼 생성자를 코드에서 직접 부르기 때문에, 스프링이
 * 빈을 어떻게 만드는지(생성자 선택, {@code @Value} 주입, 컴포넌트 스캔 범위)는 한 번도 확인하지
 * 않는다. 실제로 그 영역에서 앱이 아예 안 뜨는 사고가 났었고(PROMPT-48, 생성자 모호성), 코드를
 * 읽는 리뷰로는 구조적으로 못 잡는 종류라 테스트로 고정해 둔다.
 *
 * <p>Repository 테스트가 쓰는 컨테이너를 그대로 재사용한다 — 같은 Postgres 하나만 띄우기 위해서다.
 */
@SpringBootTest
@DisplayName("애플리케이션 컨텍스트")
class BackendApplicationContextTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainerTest.POSTGRES;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("국세청 배치 빈이 등록된 채로 컨텍스트가 뜬다")
    void loadsContextWithBatchBeans() {
        assertThat(applicationContext.getBean(NtsCheckScheduler.class)).isNotNull();
    }

    @Test
    @DisplayName("@EnableScheduling 이 살아 있어 스케줄 등록기가 존재한다")
    void enablesScheduling() {
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }
}
