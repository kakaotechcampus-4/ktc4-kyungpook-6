package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.NearbyStoreResponse;
import com.ktc4.backend.domain.store.service.NearbyStoreService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 아동 앱의 주변 가게(PROMPT-185). 관리자·점주용 가게 API({@link StoreController})와 부르는 사람이 달라 따로 둔다.
 */
@Tag(name = "아동 가게", description = "아동 앱의 주변 가게 API")
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class NearbyStoreController {

    private static final int MIN_RADIUS = 100;
    // 100km — 대구 전역이 들어온다. 시연(2026-11-12)에서 아동 앱이 가게를 전부 띄우기로 해 5km 에서 올렸다.
    private static final int MAX_RADIUS = 100_000;
    private static final int MAX_QUERY_LENGTH = 50;

    private final NearbyStoreService nearbyStoreService;

    @Operation(
            summary = "주변 가게 (아동 앱 지도·목록)",
            description = """
                    내 위치에서 반경 안의 가게를 **가까운 순**으로, 거리와 함께 돌려줍니다. 지도·목록 칩·가게 정보를 이것 하나로
                    그립니다(가게 상세 API 는 따로 없습니다). 급식카드로 인증한 아동 토큰(`Authorization: Bearer`)이 필요합니다.

                    - `q` 를 주면 **상호명이나 메뉴 이름**에 그 말이 들어간 가게만 나옵니다. 결과에 좌표가 있어 지도로 옮길 수 있습니다.
                    - 폐업·휴업 가게와 좌표가 없는 가게는 나오지 않습니다. 최대 500곳(가까운 순으로 자름)입니다.
                    - `distanceMeters` 는 요청한 위치에서 직선 거리(m, 반올림)입니다.
                    - **영업중·쉬는중 같은 상태는 앱이 계산합니다.** `openMode` 가 `MANUAL` 이면 `manualOpen` 대로(꺼져 있으면 쉬는중),
                      `SCHEDULE` 이면 `businessHours` 대로입니다.
                    - `companion`(동반 인원)은 점주가 정하는 곳이 아직 없어 지금은 늘 `NONE` 입니다.
                    - 가게·메뉴 사진은 담지 않습니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공 — 반경 안에 가게가 없으면 빈 목록"),
            @ApiResponse(responseCode = "400",
                    description = "lat·lng 가 없거나 범위를 벗어난 경우(위도 -90~90, 경도 -180~180), "
                            + "radius 가 100~100000 밖이거나 q 가 50자를 넘는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "아동 토큰이 아닌 경우(점주·관리자 토큰 포함)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/nearby")
    public List<NearbyStoreResponse> getNearbyStores(
            @Parameter(description = "내 위도", example = "35.8884")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @Parameter(description = "내 경도", example = "128.6107")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @Parameter(description = "반경(m, 100~100000). 없으면 2000", example = "2000")
            @RequestParam(defaultValue = "2000") @Min(MIN_RADIUS) @Max(MAX_RADIUS) int radius,
            @Parameter(description = "검색어 — 상호명·메뉴 이름(50자 이하). 없으면 반경 안의 가게 전부", example = "떡볶이")
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q) {
        return nearbyStoreService.findNearby(lat, lng, radius, q);
    }
}
