package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.StoreImportRequest;
import com.ktc4.backend.domain.store.dto.StoreImportResponse;
import com.ktc4.backend.domain.store.service.StoreImportService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가게 명단을 JSON 으로 넣는 관리자 전용 진입점.
 *
 * <p>경로를 {@code /api/admin/**} 아래에 둔 것은 의도적이다. {@code SecurityConfig} 에서 이 경로는
 * {@code auth.enforce} 스위치와 무관하게 항상 관리자만 부를 수 있다 — 스위치가 꺼진 동안에도 열리지 않는다.
 *
 * <p>{@code @Profile} 로 환경을 가르지 않는다. 이 API 의 용도 자체가 <b>배포 환경</b>에 자료를 넣는
 * 것이라 운영에서 빈이 사라지면 쓸 곳이 없어진다. 관리자 토큰을 가진 사람은 이미
 * {@code PATCH /api/stores/{storeId}} 로 가게를 고칠 수 있으므로, 넣는 권한을 같은 선에 두었다.
 */
@Tag(name = "가게 자료 주입(관리자)", description = "가게 명단을 JSON 으로 넣습니다")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class StoreImportAdminController {

    private final StoreImportService storeImportService;

    @Operation(
            summary = "가게 명단 주입",
            description = """
                    가게 명단을 JSON 으로 받아 넣습니다. 배포 환경에 가게 자료가 없어 관리자 프로세스를
                    끝까지 돌려 볼 수 없는 것을 푸는 경로입니다.

                    **같은 요청을 두 번 보내도 가게가 두 배로 늘지 않습니다.** 정규화한 이름과 정규화한 주소가
                    둘 다 같으면 같은 가게로 보고 건너뜁니다. 그래서 큰 명단을 여러 번에 나눠 보내도,
                    중간에 끊겨 다시 보내도 안전합니다.

                    **이미 있는 가게는 덮어쓰지 않습니다.** 담당자가 화면에서 고쳐 둔 값을 명단 재주입이
                    조용히 되돌리면 안 되기 때문입니다. 고치는 길은 `PATCH /api/stores/{storeId}` 입니다.

                    응답에 **요청 건수 · 넣은 건수 · 건너뛴 건수 · 넣지 못한 줄과 이유**가 담깁니다.
                    한 줄이 잘못돼도 나머지는 들어갑니다 — 요청 전체가 되돌아가지 않습니다.
                    다만 요청 형식 자체가 틀리면(가게명이 비었거나 길이를 넘기면) 400 으로 아무것도 넣지 않습니다.

                    이름·주소는 화면에 보여주는 원본을 그대로 보내면 됩니다. 비교용 정규화 값은 서버가 만듭니다.
                    사업자등록번호는 하이픈이 있어도 숫자만 남겨 저장하고, 10자리가 아니어도 받습니다 —
                    국세청 조회가 10자리만 걸러 씁니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "처리 완료 — 넣은 건수와 넣지 못한 줄을 반환합니다"),
            @ApiResponse(responseCode = "400",
                    description = "가게 목록이 비었거나 한 번에 보낼 수 있는 건수를 넘겼거나, "
                            + "어느 줄의 필드가 비었거나 길이를 넘긴 경우. 어느 줄이 왜 틀렸는지는 errors 배열에 담깁니다",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아닌 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/imports")
    public StoreImportResponse importStores(@Valid @RequestBody StoreImportRequest request) {
        return storeImportService.importStores(request);
    }
}
