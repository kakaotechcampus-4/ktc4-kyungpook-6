package com.ktc4.backend.domain.job.controller;

import com.ktc4.backend.domain.investigation.service.InvestigationRunner;
import com.ktc4.backend.domain.job.dto.CreatedJob;
import com.ktc4.backend.domain.job.dto.JobCreateRequest;
import com.ktc4.backend.domain.job.dto.JobCreateResponse;
import com.ktc4.backend.domain.job.dto.JobResultResponse;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "조사", description = "담당자가 고른 가게를 조사하는 API (관리자 전용)")
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobQueryService jobQueryService;
    private final InvestigationRunner investigationRunner;

    @Operation(
            summary = "조사 시작",
            description = """
                    담당자가 고른 가게로 조사를 만들고 **바로 202 로 답합니다.** 조사는 서버가 뒤에서 가게 한 곳씩 진행하고,
                    진행도와 결과는 `GET /api/jobs/{jobId}` 를 몇 초마다 불러 받습니다.

                    가게는 국세청 대조(1차 조사) 결과로 나눕니다.
                    - 국세청과 상태가 다른 가게 → AI 없이 국세청 상태로 바꾸자는 수정안을 만듭니다
                    - 국세청과 상태가 같은 가게 → AI 가 웹에서 조사합니다
                    - 조사할 수 없는 가게 → `excluded` 에 이유와 함께 담습니다 (조사 대상 수에 세지 않습니다)

                    같은 가게 ID 가 여러 번 와도 한 번만 다루고, 중복을 뺀 뒤 100곳까지 받습니다.
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "접수됨 — 조사는 뒤에서 진행됩니다"),
            @ApiResponse(responseCode = "400",
                    description = "가게 목록이 비었거나 잘못된 ID 가 있음, 100곳 초과(invalid-request), "
                            + "모두 제외돼 조사할 가게가 없음(no-investigation-target)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않음",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<JobCreateResponse> create(
            @Valid @RequestBody JobCreateRequest request,
            @AuthenticationPrincipal AuthMember admin) {

        CreatedJob created = jobService.create(request.storeIds(), admin.memberId());
        // create() 의 트랜잭션이 커밋된 뒤에 부른다 — 서비스 안에서 부르면 실행기 스레드가 아직 커밋 안 된 Job 을 못 읽는다.
        investigationRunner.run(created.plan());
        return ResponseEntity.accepted().body(JobCreateResponse.from(created));
    }

    @Operation(
            summary = "조사 진행도·결과 조회",
            description = """
                    조사 하나의 진행도(`completedCount` / `targetCount`)와 지금까지 끝난 가게의 결과를 내려줍니다.
                    화면은 몇 초마다 다시 불러 진행도를 보여 주고, `status` 가 `DONE` · `FAILED` 가 되면 그만 부릅니다.

                    - 가게별 `classification` 이 **null 이면 조사 실패**입니다 (이유는 `failureReason`)
                    - 수정안·근거는 코드값으로 내려갑니다 (`field`: status · phone · addressRoad · name, `source`: NTS · AI_WEB).
                      한글 표시는 화면이 붙입니다
                    - `FAILED` 여도 그때까지 끝난 가게의 결과는 `tasks` 에 있습니다
                    """)
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "jobId 에 해당하는 조사가 없음 (job-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/{jobId}")
    public JobResultResponse getJob(
            @Parameter(description = "조사 ID", example = "42") @PathVariable Long jobId) {
        return jobQueryService.getJob(jobId);
    }

    @Operation(summary = "가장 최근 조사의 진행도·결과 조회",
            description = "조사 ID 없이 가장 최근에 만든 조사를 내려줍니다. 응답은 `GET /api/jobs/{jobId}` 와 같습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "조사가 하나도 없음 (job-not-found)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping("/latest")
    public JobResultResponse getLatest() {
        return jobQueryService.getLatest();
    }
}
