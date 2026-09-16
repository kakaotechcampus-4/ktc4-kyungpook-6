package com.ktc4.backend.support;

import com.ktc4.backend.global.config.JpaAuditingConfig;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Repository 테스트가 실제 Postgres(Testcontainers)에 붙도록 하는 공통 베이스.
 *
 * <p>{@code @DataJpaTest}는 기본적으로 내장 DB(H2)로 치환하려 하는데, 우리는 실제 운영과 같은
 * Postgres 문법(JSONB 등)을 검증해야 하므로 {@code AutoConfigureTestDatabase.Replace.NONE}으로
 * 그 치환을 막고 Testcontainers가 띄운 Postgres를 그대로 쓴다.
 *
 * <p>{@code @DataJpaTest}는 슬라이스 테스트라 {@code JpaAuditingConfig}(created_at/updated_at
 * 자동 채우기)를 기본으로는 안 불러온다 — 그러면 BaseTimeEntity 를 상속한 엔티티를 저장할 때
 * created_at 이 null 로 들어가 NOT NULL 제약을 위반하므로 명시적으로 import 한다.
 *
 * <p><b>싱글턴 컨테이너 패턴</b>: 여러 테스트 클래스가 컨테이너 하나를 공유해야 하는데,
 * {@code @Testcontainers} + {@code @Container}를 쓰면 "그 테스트 클래스가 끝나면 컨테이너를
 * 정지"시키는 생명주기가 걸려서, 이 베이스를 상속하는 두 번째 테스트 클래스부터는 이미 죽은
 * 컨테이너에 연결하려다 실패한다(Connection refused). 그래서 여기서는 그 두 애너테이션을 쓰지 않고
 * static 초기화 블록에서 딱 한 번만 start() 하고, 이후 정리는 Testcontainers의 Ryuk 리소스 리퍼가
 * JVM 종료 시 알아서 하도록 맡긴다. {@code @ServiceConnection}은 JUnit 확장 없이 필드 인식만으로도
 * 동작하므로 그대로 둔다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(JpaAuditingConfig.class)
public abstract class PostgresContainerTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }
}
