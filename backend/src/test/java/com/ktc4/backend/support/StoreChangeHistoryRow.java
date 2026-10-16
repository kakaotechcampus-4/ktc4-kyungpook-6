package com.ktc4.backend.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 테스트에서 가게 변경 기록을 DB 에서 직접 읽은 한 줄. 값은 {@link JsonNode} 로 비교한다 — jsonb 는 공백·키 순서를
 * 정규화해 돌려주므로 문자열로 비교하면 뜻이 같아도 틀린다.
 *
 * @param field     항목
 * @param source    경로
 * @param changedBy 바꾼 회원 ID
 * @param taskId    조사 결과 ID
 * @param before    바꾸기 전 값. 없으면 {@code null}
 * @param after     바꾼 뒤 값. 없으면 {@code null}
 * @param changedAt 요청을 처리한 시각
 */
public record StoreChangeHistoryRow(StoreChangeField field, StoreChangeSource source, Long changedBy, Long taskId,
                                    JsonNode before, JsonNode after, LocalDateTime changedAt) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 가게의 변경 기록을 쌓인 순서(ID 순)로 읽는다. 같은 트랜잭션의 JPA 쓰기를 보려면 호출 전에 flush 한다.
     *
     * @param jdbcTemplate JDBC
     * @param storeId      가게 ID
     * @return 기록 줄
     */
    public static List<StoreChangeHistoryRow> of(JdbcTemplate jdbcTemplate, Long storeId) {
        return jdbcTemplate.query("""
                        select field, source, changed_by, task_id, before_value::text as before_value,
                               after_value::text as after_value, changed_at
                        from store_change_history where store_id = ? order by store_change_history_id""",
                (rs, rowNum) -> new StoreChangeHistoryRow(
                        StoreChangeField.valueOf(rs.getString("field")),
                        StoreChangeSource.valueOf(rs.getString("source")),
                        rs.getLong("changed_by"),
                        rs.getObject("task_id", Long.class),
                        json(rs.getString("before_value")),
                        json(rs.getString("after_value")),
                        rs.getTimestamp("changed_at").toLocalDateTime()),
                storeId);
    }

    /**
     * 기대값을 비교할 수 있게 JSON 문자열을 읽는다.
     *
     * @param value JSON 문자열. {@code null} 이면 {@code null}
     * @return JSON
     */
    public static JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 이 아닙니다 - " + value, e);
        }
    }
}
