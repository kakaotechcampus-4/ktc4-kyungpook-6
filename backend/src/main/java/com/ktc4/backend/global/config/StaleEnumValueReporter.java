package com.ktc4.backend.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기동할 때, 아직 검증되지 않은(NOT VALID) CHECK 제약이 남아 있으면 알린다.
 *
 * <p>그런 제약이 남아 있다는 것은 <b>코드가 모르는 값이 그 칸에 들어 있다</b>는 뜻이다. DB 에서는
 * 살아 있다가 JPA 가 읽을 때 {@code valueOf} 에서 터지므로, 배포는 멈추지 않고 그 행을 읽는 화면만
 * 500 이 된다. 찾기 어려운 고장이라 기동할 때 한 번 크게 적어 둔다.
 *
 * <p>마이그레이션 안의 {@code RAISE WARNING} 으로는 부족해서 여기에 둔다 — 그 경고는 Flyway 로그에만
 * 남아 배포 로그에 묻힌다.
 *
 * <p>조회에 실패해도 기동을 멈추지 않는다. 이것은 알림이지 검사가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleEnumValueReporter implements ApplicationRunner {

    private static final String NOT_VALIDATED_CHECKS = """
            SELECT rel.relname || '.' || con.conname
              FROM pg_constraint con
              JOIN pg_class rel ON rel.oid = con.conrelid
              JOIN pg_namespace ns ON ns.oid = rel.relnamespace
             WHERE con.contype = 'c' AND ns.nspname = 'public' AND NOT con.convalidated
             ORDER BY 1
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<String> stale;
        try {
            stale = jdbcTemplate.queryForList(NOT_VALIDATED_CHECKS, String.class);
        } catch (RuntimeException e) {
            log.warn("검증되지 않은 CHECK 제약을 조회하지 못했습니다", e);
            return;
        }

        if (stale.isEmpty()) {
            return;
        }
        log.error("""
                코드가 모르는 값이 남아 있어 검증되지 않은 CHECK 제약이 {}개 있습니다: {}
                그 값을 읽는 화면이 500 으로 떨어집니다. 어떤 값이 남았는지 보고 옮기는 마이그레이션을 더하세요 \
                (V5__migrate_renamed_enum_values.sql 참고). 옮긴 뒤에는 ALTER TABLE ... VALIDATE CONSTRAINT 가 올라갑니다.""",
                stale.size(), stale);
    }
}
