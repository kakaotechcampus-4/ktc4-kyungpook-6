package com.ktc4.backend.domain.call.controller;

import com.ktc4.backend.domain.call.service.CallLogService;
import com.ktc4.backend.global.error.ApiProblemDetail;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "확인 전화 로그", description = "관리자용 통화 대화·Jev 판정 조회")
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallLogController {

    private final CallLogService callLogService;

    @Operation(summary = "통화 대화와 Jev 판정 조회",
            description = "전화 기록의 callId로 조회합니다. 응답의 nextCursor를 다음 요청의 after에 넣으면 새 로그만 받습니다. 성공 응답은 전화 API의 JSON을 그대로 전달합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 통화 상태와 새 대화·Jev 로그",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "400", description = "after가 음수이거나 숫자가 아님",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "로그인하지 않음",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "관리자가 아님",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "callId에 해당하는 전화 기록이 없음",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "전화 API 조회 실패",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "전화 API 설정이 없음",
                    content = @Content(schema = @Schema(implementation = ApiProblemDetail.class)))
    })
    @GetMapping(value = "/{callId}/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getLogs(@PathVariable Long callId,
                                          @RequestParam(defaultValue = "0") int after) {
        if (after < 0) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(callLogService.read(callId, after));
    }
}
