package com.ktc4.backend.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 페이지네이션 공통 응답 포맷.
 *
 * <p>Spring 의 {@link Page} 를 그대로 직렬화하면 화면에서 쓰지 않는 필드가 여럿 딸려 나오고,
 * 그 구조가 Spring 버전에 따라 달라진다. 응답 형태를 우리가 직접 고정하기 위해 감싼다.
 *
 * @param hasNext 다음 페이지 존재 여부. 프론트가 총 페이지 수로 직접 계산하지 않도록 서버가 내려준다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    /**
     * 엔티티 페이지를 응답 DTO 페이지로 변환한다.
     *
     * @param page   조회 결과 페이지
     * @param mapper 엔티티 → 응답 DTO 변환 함수
     */
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
