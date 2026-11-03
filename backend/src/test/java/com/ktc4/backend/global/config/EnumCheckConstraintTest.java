package com.ktc4.backend.global.config;

import com.ktc4.backend.support.PostgresContainerTest;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드의 enum 값과 DB 의 CHECK 제약이 같은 값을 갖는지 본다.
 *
 * <p><b>왜 필요한가</b> — {@code ddl-auto: validate} 는 표·컬럼·타입만 보고 <b>CHECK 제약은 보지
 * 않는다.</b> 그래서 enum 에 값을 더하거나 이름을 바꿔도 기동이 멈추지 않고, 그 값을 처음 저장하는
 * 순간에야 DB 가 거절한다. 2026-09-18 부터 한 달 가까이 배포본의 AI 조사가 죽어 있던 것이 그 경로다
 * (PROMPT-159). 11주차 멘토 리뷰에서 "그 자리를 테스트로 잡아 두라" 는 지적을 받아 둔다.
 *
 * <p>스키마는 Flyway 가 만든다({@code flyway.enabled: true}). 그래서 여기서 비교하는 CHECK 는
 * Hibernate 가 방금 만든 것이 아니라 <b>마이그레이션에 적힌 것</b>이고, 마이그레이션을 빠뜨리면
 * 이 테스트가 깨진다. 그게 이 테스트의 목적이다.
 *
 * <p>잡지 <b>못하는</b> 것: 이미 배포된 DB 에 남은 옛 값. 그건 기동할 때
 * {@link StaleEnumValueReporter} 가 본다.
 */
@DisplayName("코드 enum 과 DB CHECK 제약")
class EnumCheckConstraintTest extends PostgresContainerTest {

    // CHECK 정의에서 비교 대상 컬럼을 뽑는다 — 예: ((classification)::text = ANY (...))
    private static final Pattern CHECKED_COLUMN = Pattern.compile("\\((\\w+)\\)::text");

    // 허용 값 목록을 뽑는다 — 예: 'PRIORITY_CHECK'::character varying
    private static final Pattern ALLOWED_VALUE = Pattern.compile("'([^']*)'::character varying");

    private static final String CHECK_DEFINITIONS = """
            SELECT pg_get_constraintdef(con.oid)
              FROM pg_constraint con
              JOIN pg_class rel ON rel.oid = con.conrelid
              JOIN pg_namespace ns ON ns.oid = rel.relnamespace
             WHERE con.contype = 'c' AND ns.nspname = 'public' AND rel.relname = ?
            """;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("enum 칸마다 CHECK 가 있고 허용 값이 코드와 정확히 같다")
    void everyEnumColumnMatchesItsCheckConstraint() {
        List<EnumColumn> columns = enumColumns();
        // 엔티티가 줄어든 것을 눈치채지 못하고 0건을 통과시키지 않게 한다
        assertThat(columns).as("enum 으로 매핑된 칸").isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (EnumColumn column : columns) {
            Set<String> inDatabase = allowedValues(column);
            if (inDatabase == null) {
                problems.add("%s.%s — CHECK 제약이 없습니다. 코드 값: %s"
                        .formatted(column.table(), column.column(), sorted(column.values())));
                continue;
            }
            if (!inDatabase.equals(column.values())) {
                problems.add("""
                        %s.%s — 허용 값이 다릅니다
                          코드에만: %s
                          DB 에만 : %s"""
                        .formatted(column.table(), column.column(),
                                sorted(minus(column.values(), inDatabase)),
                                sorted(minus(inDatabase, column.values()))));
            }
        }

        assertThat(problems)
                .as("""
                        enum 과 CHECK 제약이 어긋났습니다. db/migration 에 마이그레이션을 더해 맞추세요 \
                        (값을 더하기만 했으면 V4, 이름을 바꿨으면 V5 를 참고하세요).""")
                .isEmpty();
    }

    /** 엔티티에 {@code @Enumerated} 로 매핑된 칸과 그 enum 이 가진 값. */
    private List<EnumColumn> enumColumns() {
        List<EnumColumn> columns = new ArrayList<>();
        for (EntityType<?> entity : entityManager.getMetamodel().getEntities()) {
            Class<?> javaType = entity.getJavaType();
            for (Field field : javaType.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Enumerated.class) || !field.getType().isEnum()) {
                    continue;
                }
                Set<String> values = Arrays.stream(field.getType().getEnumConstants())
                        .map(constant -> ((Enum<?>) constant).name())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                columns.add(new EnumColumn(tableName(javaType), columnName(field), values));
            }
        }
        return columns;
    }

    /**
     * 그 칸 하나만 보는 CHECK 제약의 허용 값. 제약이 없으면 {@code null}.
     *
     * <p>칸 여러 개를 함께 보는 CHECK(영업시간 분 범위 등)는 건너뛴다 — 허용 값 목록이 아니다.
     */
    private Set<String> allowedValues(EnumColumn column) {
        List<String> definitions = jdbcTemplate.queryForList(CHECK_DEFINITIONS, String.class, column.table());
        for (String definition : definitions) {
            Set<String> checkedColumns = matches(CHECKED_COLUMN, definition);
            if (checkedColumns.size() != 1 || !checkedColumns.contains(column.column())) {
                continue;
            }
            return matches(ALLOWED_VALUE, definition);
        }
        return null;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> minus(Set<String> from, Set<String> other) {
        return from.stream().filter(value -> !other.contains(value))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isBlank() ? table.name() : snake(entity.getSimpleName());
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.name().isBlank() ? column.name() : snake(field.getName());
    }

    // 스프링 부트 기본 네이밍(CamelCaseToUnderscoresNamingStrategy)과 같은 규칙
    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private record EnumColumn(String table, String column, Set<String> values) {
    }
}
