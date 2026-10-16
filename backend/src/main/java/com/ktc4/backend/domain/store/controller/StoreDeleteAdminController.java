package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.StoreDeleteResponse;
import com.ktc4.backend.domain.store.dto.StoreDeletionRequest;
import com.ktc4.backend.domain.store.dto.StoreDeletionResponse;
import com.ktc4.backend.domain.store.service.StoreDeletionService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가게를 지우는 관리자 전용 진입점.
 *
 * <p>{@link StoreImportAdminController}(넣기)와 같은 경로 아래 둔다 — 넣는 길과 지우는 길이 한자리에서
 * 보이게 하려는 것이다. {@code /api/admin/**} 은 {@code SecurityConfig} 에서 관리자만 부를 수 있다.
 *
 * <p>클래스를 따로 둔 이유는 하나다. 주입 쪽 Swagger 설명이 이미 길어서, 지우기를 같은 클래스에 넣으면
 * 문서에서 두 일이 섞여 읽힌다.
 */
@Tag(name = "가게 삭제(관리자)", description = "잘못 넣은 가게나 시험용 자료를 되돌립니다")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class StoreDeleteAdminController {

    private final StoreDeletionService storeDeletionService;

    @Operation(
            summary = "가게 한 곳 삭제",
            description = """
                    가게 한 곳을 **딸린 기록과 함께** 지웁니다. 되돌릴 수 없습니다.

                    함께 지우는 것 — 국세청 확인 기록·상태 변경 이력·메뉴·영업시간·조사 결과·확인 기록·신호.
                    응답에 각각 몇 줄을 지웠는지 담습니다. 조사(`job`) 자체는 남습니다 —
                    어떤 조사를 언제 돌렸는지는 가게와 별개의 기록입니다.

                    **실제 활동이 남은 가게는 지우지 않고 409 로 거절합니다.**

                    | 거절 | 왜 |
                    |---|---|
                    | 점주가 연결돼 있음 | 쓰던 가게가 말없이 사라지면 안 됩니다. `DELETE /api/admin/owners/{memberId}/stores/{storeId}` 로 연결을 먼저 끊으세요 |
                    | 끝나지 않은 조사에 들어 있음 | AI 서버가 결과를 돌려줄 때 가리킬 가게가 없어집니다. 조사가 끝난 뒤에 지우세요 |
                    | 방문(체크인) 기록이 있음 | 아동이 실제로 다녀간 기록입니다 |

                    가게 자료를 **고치는** 길은 `PATCH /api/stores/{storeId}` 입니다. 이름·주소가 틀린
                    정도라면 지우고 다시 넣지 말고 고치세요 — 지우면 조사 이력까지 사라집니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "지웠습니다 — 함께 지운 기록의 줄 수를 반환합니다"),
            @ApiResponse(responseCode = "401", description = "로그인하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "그 가게가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "점주가 연결돼 있거나, 끝나지 않은 조사에 들어 있거나, 방문 기록이 있는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @DeleteMapping("/{storeId}")
    public StoreDeleteResponse deleteStore(
            @Parameter(description = "지울 가게 ID", example = "12") @PathVariable Long storeId) {
        return storeDeletionService.delete(storeId);
    }

    @Operation(
            summary = "가게 여러 곳 삭제",
            description = """
                    가게 여러 곳을 한 번에 지웁니다. 되돌릴 수 없습니다.
                    `POST /api/admin/stores/imports` 로 넣은 묶음을 되돌리는 길입니다.

                    **한 곳이 막혀도 나머지는 지웁니다.** 어느 가게를 왜 지우지 않았는지는 `refusals` 에
                    담깁니다 — 단건 API 의 409 조건과 같습니다.

                    **이미 없는 ID 는 오류가 아닙니다.** `notFound` 로 셉니다. 같은 요청을 두 번 보내도
                    두 번째가 404 로 끊기지 않습니다(넣는 쪽이 두 번 보내도 안전한 것과 짝을 맞췄습니다).

                    한 번에 보낼 수 있는 상한은 **100건**입니다. 주입(500건)보다 작게 둔 것은 의도입니다 —
                    지우기는 되돌릴 수 없어서, 한 번의 실수로 사라지는 양을 작게 하는 편이 낫습니다.

                    예상 못 한 오류가 중간에 나면 **그 요청 전체가 되돌아갑니다.** 절반만 지워진 상태로
                    끝나는 쪽이 더 나쁘기 때문입니다. 거절은 오류가 아니라 결과로 담깁니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "처리 완료 — 지운 건수와 거절한 가게를 반환합니다"),
            @ApiResponse(responseCode = "400", description = "ID 목록이 비었거나 한 번에 보낼 수 있는 건수를 넘긴 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/deletions")
    public StoreDeletionResponse deleteStores(@Valid @RequestBody StoreDeletionRequest request) {
        return storeDeletionService.deleteAll(request);
    }
}
