# Swagger API 가이드

우리 프로젝트의 **API 명세 자동화** 및 **테스트**를 위한 가이드입니다.
코드를 작성하면 실시간으로 문서가 업데이트되며, 웹에서 바로 API를 호출해 볼 수 있습니다.

- **접속 주소**: `http://localhost:8080/swagger-ui/index.html` (실제 서버 실행 후 확인 완료 — `/ping`, `/test/echo` 정상 등록됨)
- **실행 조건**: 로컬 서버가 실행 중이어야 접속 가능합니다.

### 스웨거 UI 사용법

1. 원하는 엔드포인트 클릭해서 펼치기
2. 오른쪽 위 **Try it out** 클릭 (눌러야 입력 필드가 활성화됨)
3. 파라미터/요청 body 입력
4. **Execute** 클릭 → 아래에 실제 응답 결과 표시됨

---

### 핵심 어노테이션

| 어노테이션 | 위치 | 역할 |
| :--- | :--- | :--- |
| **@Tag** | 클래스 상단 | 도메인별 API 그룹핑 (Order, Payment 등) |
| **@Operation** | 메서드 상단 | API 기능의 한글 명칭 및 상세 설명 |
| **@Schema** | DTO 필드 | 데이터 설명 및 **테스트용 예시값(example)** 설정 |
| **@ApiResponse** | 메서드 상단 | 성공(200) 및 실패(400, 500 등) 상황별 응답 설명 |
| **@Parameter** | `@PathVariable`/`@RequestParam` | 경로·쿼리 파라미터 설명 및 예시값 설정 (`page`, `limit` 같은 페이지네이션 파라미터에 사용) |

#### 테스트 컨트롤러 예시
```java
@Tag(name = "테스트 API", description = "스웨거 테스트")
@RestController
public class TestController {

    @Operation(summary = "스웨거 테스트", description = "잘 돌아가네요^^")
    @GetMapping("/ping")
    public String ping() {
        return "pong";
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

    // 테스트 DTO(원래는 다른 파일에)
    @Getter
    static class TestRequest {
        @Schema(description = "서버로 보낼 메세지 테스트", example = "하이열 ㅋㅋ")
        private String message;
    }
}
```

#### 사용 예시
```java
@Tag(name = "Order", description = "주문 생성 및 관리 API")
@RestController
public class OrderController {

    @Operation(summary = "주문 생성", description = "상품과 결제 정보를 입력받아 주문을 생성합니다.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "주문 생성 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 형식"),
        @ApiResponse(responseCode = "500", description = "결제사 통신 오류")
    })
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) { ... }

    @Operation(summary = "주문 목록 조회", description = "페이지 단위로 주문 목록을 조회합니다.")
    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> getOrders(
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") @RequestParam int page,
            @Parameter(description = "한 번에 불러올 개수", example = "20") @RequestParam int limit
    ) { ... }
}

public class OrderRequest {
    @Schema(description = "주문 금액", example = "15000")
    private long amount;
}
```

---

### 참고사항

- Controller 메서드 → 스웨거만 (Javadoc 생략)
- Service/인터페이스/외부 API 클라이언트 메서드 → Javadoc만 (스웨거 대상 자체가 아님)
