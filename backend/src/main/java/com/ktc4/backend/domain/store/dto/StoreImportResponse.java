package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 가게 자료 주입 결과.
 *
 * <p>건수만 주면 "232건 보냈는데 180건만 들어갔다" 에서 멈춘다. 어느 줄이 왜 빠졌는지까지 담아,
 * 배포 환경에 들어가 보지 않고도 보낸 쪽에서 원인을 알 수 있게 한다.
 *
 * @param requested 요청에 담겨 온 건수
 * @param inserted  새로 넣은 건수
 * @param skipped   이미 있어서 건너뛴 건수 (요청 안에서 같은 가게가 두 번 나온 것도 포함)
 * @param rejected  넣지 못한 건수 — {@code rejections} 의 길이와 같다
 * @param rejections 넣지 못한 줄과 이유
 */
public record StoreImportResponse(
        @Schema(description = "요청에 담겨 온 건수", example = "232")
        int requested,

        @Schema(description = "새로 넣은 건수", example = "230")
        int inserted,

        @Schema(description = "이미 있어서 건너뛴 건수 (요청 안의 중복 포함)", example = "1")
        int skipped,

        @Schema(description = "넣지 못한 건수", example = "1")
        int rejected,

        @Schema(description = "넣지 못한 줄과 이유")
        List<Rejection> rejections
) {

    /**
     * 넣지 못한 한 줄.
     *
     * @param index  요청 목록에서의 위치(0부터). 보낸 파일의 어느 줄인지 찾는 데 쓴다
     * @param name   그 줄의 가게명 — 번호만으로는 어느 가게인지 알 수 없다
     * @param reason 넣지 못한 이유
     */
    public record Rejection(
            @Schema(description = "요청 목록에서의 위치(0부터)", example = "17")
            int index,

            @Schema(description = "그 줄의 가게명", example = "!!!")
            String name,

            @Schema(description = "넣지 못한 이유", example = "가게명을 정규화하면 남는 글자가 없습니다")
            String reason
    ) {
    }

    /**
     * 결과를 만든다. {@code rejected} 는 따로 받지 않고 {@code rejections} 에서 센다 —
     * 두 값이 어긋날 수 있는 길을 두지 않는다.
     *
     * @param requested  요청에 담겨 온 건수
     * @param inserted   새로 넣은 건수
     * @param skipped    이미 있어서 건너뛴 건수
     * @param rejections 넣지 못한 줄과 이유
     * @return 주입 결과
     */
    public static StoreImportResponse of(int requested, int inserted, int skipped, List<Rejection> rejections) {
        return new StoreImportResponse(requested, inserted, skipped, rejections.size(), List.copyOf(rejections));
    }
}
