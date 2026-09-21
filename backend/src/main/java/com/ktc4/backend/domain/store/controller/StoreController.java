package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "가게", description = "가게 정보 조회·수정 API")
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private static final int MAX_LIMIT = 100;

    private final StoreService storeService;

    @Operation(summary = "가게 목록 조회", description = "가게 목록을 storeId 오름차순으로 페이지 단위 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "page 가 음수이거나 limit 이 1~100 범위를 벗어난 경우",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public PageResponse<StoreResponse> getStores(
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 페이지당 건수 (1~100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMIT) int limit) {

        return storeService.getStores(page, limit);
    }

    @Operation(
            summary = "가게 정보 수정 (스펙 선공개 — 아직 동작하지 않음)",
            description = """
                    담당자가 가게 기본 정보를 직접 고칩니다. 담아 보낸 필드만 바뀌고, 빠뜨린 필드는 기존 값을 유지합니다.

                    **현재는 스펙만 공개된 단계라 실제 수정은 일어나지 않고 501 을 반환합니다.**
                    프론트가 이 스펙으로 타입을 생성해 화면을 먼저 만들 수 있도록 먼저 올립니다.
                    아래 200 / 400 / 404 는 다음 PR에서 구현될 응답 계약입니다.

                    확인일 갱신은 이 API가 아니라 `POST /api/stores/{storeId}/confirm` 입니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "[구현 예정] 수정 성공 — 수정된 가게 정보를 반환합니다"),
            @ApiResponse(responseCode = "400",
                    description = "필드 길이가 허용 범위를 벗어나거나 status 값이 잘못된 경우. "
                            + "지금은 본문이 스프링 기본 검증 오류 형태로 나가며, 에러 규격화 티켓에서 ErrorResponse 로 통일됩니다 "
                            + "— 그때까지 이 응답 본문에 의존하지 마세요",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "[구현 예정] storeId 에 해당하는 가게가 없는 경우",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "501", description = "현재 이 응답만 실제로 나옵니다 — 아직 구현되지 않음",
                    content = @Content)
    })
    @PatchMapping("/{storeId}")
    public ResponseEntity<StoreResponse> updateStore(
            @Parameter(description = "수정할 가게 ID", example = "1")
            @PathVariable Long storeId,
            @Valid @RequestBody StoreUpdateRequest request) {

        // 스펙 선공개용 스텁. 파라미터를 쓰지 않는 것이 의도이며, 구현 PR에서 이 줄이
        // storeService 호출로 교체되면서 이 주석과 함께 사라진다.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Operation(
            summary = "가게 자체 확인 완료 (스펙 선공개 — 아직 동작하지 않음)",
            description = """
                    담당자가 가게 정보를 직접 확인했음을 기록합니다. 요청 본문은 없고, 확인 시각은 서버가 현재 시각으로 찍습니다.

                    수정 API와 나눈 이유는 두 가지입니다.
                    1. PATCH 는 같은 요청을 여러 번 보내도 결과가 같아야 하는데, 확인 기록은 호출할 때마다 시각이 바뀝니다.
                    2. 이 시각은 이후 "확인한 지 오래된 가게 재조사" 기준이 되므로 클라이언트가 값을 정하면 안 됩니다.

                    **현재는 스펙만 공개된 단계라 실제 기록은 일어나지 않고 501 을 반환합니다.**
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "[구현 예정] 기록 성공 — 확인 시각이 갱신된 가게 정보를 반환합니다"),
            @ApiResponse(responseCode = "404", description = "[구현 예정] storeId 에 해당하는 가게가 없는 경우",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "501", description = "현재 이 응답만 실제로 나옵니다 — 아직 구현되지 않음",
                    content = @Content)
    })
    @PostMapping("/{storeId}/confirm")
    public ResponseEntity<StoreResponse> confirmStore(
            @Parameter(description = "확인 완료 처리할 가게 ID", example = "1")
            @PathVariable Long storeId) {

        // 스펙 선공개용 스텁. storeId 를 쓰지 않는 것이 의도이며(죽은 코드가 아니다),
        // 구현 PR에서 이 줄이 storeService 호출로 교체되면서 이 주석과 함께 사라진다.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
