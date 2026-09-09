package com.ktc4.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "테스트 API", description = "스웨거 테스트")
@RestController
public class TestController {

    @Operation(summary = "스웨거 테스트", description = "잘 돌아가네요^^")
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @Operation(summary = "쿼리 파라미터 테스트", description = "페이지네이션에 쓸 @Parameter 연습용입니다.")
    @GetMapping("/test/page")
    public String page(
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") @RequestParam int page,
            @Parameter(description = "한 번에 불러올 개수", example = "20") @RequestParam int limit
    ) {
        return page + "페이지, " + limit + "개씩";
    }

    @Operation(summary = "DTO 및 응답 테스트", description = "데이터를 입력받아 그대로 돌려주는 기능입니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/test/echo")
    public String echo(@RequestBody TestRequest request) {
        return "보낸 메세지: " + request.getMessage();
    }

    @Getter
    static class TestRequest {
        @Schema(description = "서버로 보낼 메세지 테스트", example = "하이열 ㅋㅋ")
        private String message;
    }
}
