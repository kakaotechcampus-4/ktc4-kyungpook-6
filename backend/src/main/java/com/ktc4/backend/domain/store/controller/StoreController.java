package com.ktc4.backend.domain.store.controller;

import com.ktc4.backend.domain.store.dto.OperatingInfoRequest;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreCheckResponse;
import com.ktc4.backend.domain.store.dto.StoreDetailResponse;
import com.ktc4.backend.domain.store.dto.StoreResponse;
import com.ktc4.backend.domain.store.dto.StoreUpdateRequest;
import com.ktc4.backend.domain.store.enums.NtsCheckFilter;
import com.ktc4.backend.domain.store.service.StoreManagementService;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.security.AuthMember;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final StoreManagementService storeManagementService;

    @Operation(summary = "가게 목록 조회", description = "가게 목록을 storeId 오름차순으로 페이지 단위 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "page 가 음수이거나 limit 이 1~100 범위를 벗어난 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
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
            summary = "국세청 대조 자료 조회",
            description = """
                    가게 정보와 국세청 사업자 상태를 나란히 담아 내려줍니다. AI 1차 조사와 담당자 데이터 정리에 씁니다.

                    국세청은 이 API 가 직접 호출하지 않고, 매일 새벽 배치가 확인해 저장해 둔 값을 읽습니다.
                    그 값이 언제 기준인지는 `ntsCheckedAt`(마지막으로 확인에 성공한 시각)으로 알 수 있고,
                    아직 확인되지 않은 가게는 `ntsLookup` 이 `UNCONFIRMED` 로 내려갑니다.
                    조회에 실패한 날에도 `ntsStatus` 는 마지막으로 확인된 값이 남아 판정에 쓰입니다.

                    대응이 다른 두 가지를 나눠 담습니다.
                    - `statusMismatch`: 우리 상태와 국세청 상태가 다름 → **AI 조사 대상**
                    - `dataProblem`: 사업자번호가 없거나 국세청에 없는 번호 → **번호부터 찾거나 바로잡을 대상**

                    `filter` 로 둘 중 하나만 받아볼 수 있고, 주지 않으면 전체가 내려갑니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400",
                    description = "filter 값이 잘못됐거나, page 가 음수이거나 limit 이 1~100 범위를 벗어난 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/nts-checks")
    public PageResponse<StoreCheckResponse> getNtsChecks(
            @Parameter(description = "STATUS_MISMATCH(상태 불일치) 또는 DATA_PROBLEM(번호 없음·틀림). 없으면 전체",
                    example = "STATUS_MISMATCH")
            @RequestParam(required = false) NtsCheckFilter filter,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 페이지당 건수 (1~100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_LIMIT) int limit) {

        return storeService.getNtsChecks(filter, page, limit);
    }

    @Operation(
            summary = "가게 정보 수정",
            description = """
                    담당자가 가게 기본 정보를 직접 고칩니다. 담아 보낸 필드만 바뀌고, 빠뜨린 필드는 기존 값을 유지합니다.
                    바뀐 항목은 누가 무엇에서 무엇으로 바꿨는지 변경 기록에 남습니다.

                    확인일 갱신은 이 API가 아니라 `POST /api/stores/{storeId}/confirm` 입니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "수정 성공 — 수정된 가게 정보를 반환합니다"),
            @ApiResponse(responseCode = "400",
                    description = "필드 길이가 허용 범위를 벗어나거나 status 값이 잘못된 경우. "
                            + "어느 필드가 왜 틀렸는지는 errors 배열에 담깁니다",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PatchMapping("/{storeId}")
    public StoreResponse updateStore(
            @Parameter(description = "수정할 가게 ID", example = "1")
            @PathVariable Long storeId,
            @Valid @RequestBody StoreUpdateRequest request,
            @AuthenticationPrincipal AuthMember admin) {

        return storeService.updateStore(storeId, request, StoreChange.storeEdit(admin.memberId()));
    }

    @Operation(
            summary = "가게 자체 확인 완료",
            description = """
                    담당자가 가게 정보를 직접 확인했음을 기록합니다. 요청 본문은 없고, 확인 시각은 서버가 현재 시각으로 찍습니다.

                    수정 API와 나눈 이유는 두 가지입니다.
                    1. PATCH 는 같은 요청을 여러 번 보내도 결과가 같아야 하는데, 확인 기록은 호출할 때마다 시각이 바뀝니다.
                    2. 이 시각은 이후 "확인한 지 오래된 가게 재조사" 기준이 되므로 클라이언트가 값을 정하면 안 됩니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "기록 성공 — 확인 시각이 갱신된 가게 정보를 반환합니다"),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping("/{storeId}/confirm")
    public StoreResponse confirmStore(
            @Parameter(description = "확인 완료 처리할 가게 ID", example = "1")
            @PathVariable Long storeId) {

        return storeService.confirmStore(storeId);
    }

    @Operation(
            summary = "가게 상세 조회 (점주 앱 홈·가게 관리)",
            description = """
                    가게 한 곳의 상호명·주소·전화번호·영업시간·메뉴를 내려줍니다. 점주 앱은 `GET /api/auth/me` 의
                    `stores[].storeId` 로 부릅니다.

                    - 관리자와 **그 가게에 연결된 점주**만 부를 수 있습니다. 다른 가게면 `forbidden` 입니다.
                    - 상호명·주소는 점주가 바꿀 수 없는 값입니다(가게 관리 화면에서 잠금).
                    - 영업시간은 요일 순(월 → 일), 같은 요일은 여는 시간 순입니다. 메뉴는 지운 것을 빼고 등록한 순서입니다.
                    - 가게 사진은 담지 않습니다. `GET /api/stores/{storeId}/images` 로 받으세요.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우. "
                    + "점주에게는 없는 가게 번호여도 403 입니다(가게가 있는지 알려 주지 않음, 체크인과 같음)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우 (관리자만)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/{storeId}")
    public StoreDetailResponse getStore(
            @Parameter(description = "가게 ID", example = "12")
            @PathVariable Long storeId,
            @AuthenticationPrincipal AuthMember authMember) {
        return storeManagementService.getDetail(storeId, authMember);
    }

    @Operation(
            summary = "가게 운영 정보 저장 (점주 앱 가게 관리 \"저장\")",
            description = """
                    가게 전화번호·영업시간·메뉴를 **한 번에 통째로** 바꿉니다. 전부 반영되거나 하나도 반영되지 않습니다.

                    - 세 칸 모두 보내야 합니다. 영업시간은 1~21줄, 메뉴는 1~50개이고 가게 전화번호도 비울 수 없습니다
                      (가입 때 필수였던 값을 저장으로 지울 수 없게).
                    - 메뉴: `menuId` 가 있으면 그 메뉴를 고치고, 없으면 새로 만들고, 목록에서 빠진 메뉴는 삭제됩니다.
                      화면에서 메뉴를 등록·삭제하거나 판매 토글을 바꿔도 "저장"을 눌러야 반영됩니다.
                      삭제한 메뉴는 되살릴 수 없습니다 — 같은 이름으로 새로 만드세요.
                    - 시간은 `HH:mm` 입니다. 자정에 닫으면 `24:00`, 여는 시간보다 이르면 다음 날 닫는다는 뜻(예: 21:00~02:00),
                      24시간 영업은 `00:00`~`24:00` 입니다.
                    - 상호명·주소는 이 API 로 바꿀 수 없습니다.
                    - 관리자와 **그 가게에 연결된 점주**만 부를 수 있습니다.

                    형식 오류는 `errors` 에 몇 번째 줄의 어느 칸인지(예: `businessHours[1].open`, `menus[0].onSale`) 담깁니다.
                    영업시간끼리 겹치면(같은 요일, 또는 자정을 넘긴 시간과 다음 날 시간) `errors` 에 `businessHours` 칸으로 담깁니다.
                    같은 `menuId` 를 두 번 보내면 `errors` 에 `menus` 칸으로 담깁니다.
                    요일 값만은 읽는 단계에서 걸려 `errors` 없이 `invalid-request` 입니다(MON~SUN).

                    본문 형식은 권한보다 먼저 검사합니다 — 형식이 틀린 본문은 연결되지 않은 가게여도 403 이 아니라 400 입니다.

                    **`errors` 없는 `invalid-request` 는 화면이 오래됐다는 뜻입니다 — 다시 불러온 뒤 저장하세요.**
                    보낸 `menuId` 가 이 가게의 메뉴가 아니거나 이미 삭제된 경우입니다(대개 다른 점주가 먼저 저장한 경우).
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "저장 성공 — 저장된 가게 상세를 돌려줍니다"),
            @ApiResponse(responseCode = "400",
                    description = "형식·개수가 틀리거나 영업시간이 겹치는 경우(errors 배열, 겹침은 businessHours 칸) — "
                            + "같은 요일 시간대끼리, 또는 자정을 넘긴 시간과 다음 날 시간이 겹치는 경우. "
                            + "같은 menuId 를 두 번 보낸 경우(errors 의 menus 칸). "
                            + "보낸 menuId 가 이 가게의 메뉴가 아니거나 이미 삭제된 경우(errors 없음 — 다시 불러오기)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "토큰이 없거나 유효하지 않은 경우",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자도 아니고 이 가게에 연결된 점주도 아닌 경우. "
                    + "점주에게는 없는 가게 번호여도 403 입니다(가게가 있는지 알려 주지 않음, 체크인과 같음)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "storeId 에 해당하는 가게가 없는 경우 (관리자만)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PutMapping("/{storeId}/operating-info")
    public StoreDetailResponse saveOperatingInfo(
            @Parameter(description = "가게 ID", example = "12")
            @PathVariable Long storeId,
            @Valid @RequestBody OperatingInfoRequest request,
            @AuthenticationPrincipal AuthMember authMember) {
        return storeManagementService.save(storeId, request, authMember);
    }
}
