package com.ktc4.backend.global.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 페이지네이션 공통 응답 포맷.
 *
 * <p>Spring 의 {@link Page} 를 그대로 직렬화하면 화면에서 쓰지 않는 필드가 여럿 딸려 나오고,
 * 그 구조가 Spring 버전에 따라 달라진다. 응답 형태를 직접 고정하기 위해 감싼다.
 */
public record PageResponse<T>(
        @Schema(description = "조회된 항목 목록")
        List<T> content,

        @Schema(description = "현재 페이지 번호(0부터 시작)", example = "0")
        int page,

        @Schema(description = "한 페이지당 건수", example = "20")
        int limit,

        @Schema(description = "전체 항목 수", example = "50")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "3")
        int totalPages,

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext
) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
