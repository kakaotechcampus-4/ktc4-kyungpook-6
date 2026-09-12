# Javadoc 가이드

코드 가독성과 팀원 팔로업을 위해 주요 메서드에 Javadoc을 작성합니다.
정형화된 주석은 사람뿐 아니라 AI 에이전트가 코드를 파악할 때 좋을 거 같습니다.
IntelliJ에서 메서드에 마우스를 올리면 팝업으로 설명이 표시됩니다.

---

### 작성 대상

| 대상                        | 작성 여부              |
|:--------------------------|:-------------------|
| 인터페이스 `public` 메서드        | ✅ 필수               |
| 서비스 레이어 `public` 메서드      | ✅ 필수               |
| 외부 API 클라이언트 `public` 메서드 (밖으로 나가는 호출, `@RestController` 아님) | ✅ 필수               |
| Controller 메서드 (밖에서 들어오는 요청 처리) | ❌ 생략 (Swagger `@Operation`이 담당 — 두 군데서 따로 관리하면 어긋남, 예시는 `스웨거_API_문서화_가이드.md` 참고) |
| `@Override` 구현체 메서드       | ❌ 생략 (인터페이스에서 상속됨) |
| Spring Data JPA 파생 쿼리 메서드 (`findBy...`, `existsBy...` 등) | ❌ 생략 (메서드명으로 의도 명확) |
| 테스트(`@Test`) 메서드          | ❌ 생략 (설명적인 메서드명/`@DisplayName`으로 대체) |
| 예외 클래스, `ErrorCode` 등 enum | ❌ 생략 (이름/메시지로 의도 명확) |
| `@RestControllerAdvice`/`@ExceptionHandler` 메서드 | ❌ 생략 (Controller와 같은 이유) |
| DTO 필드                    | ❌ 생략 (필드명으로 충분)    |
| `private` 메서드             | ❌ 생략               |

> 인터페이스+구현체로 만든 외부 API 클라이언트인 경우 `@Override` 쪽이 우선 — 인터페이스 쪽에 이미 Javadoc이 있을 거임

---

### 기본 형식

파라미터, 반환값, 예외가 없는 경우 해당 태그는 생략합니다.

```java
/**
 * 한 줄 기능 설명
 *
 * @param 파라미터명 설명
 * @return 반환값 설명
 * @throws 예외 발생 조건
 */
```

---

### 작성 예시

#### 인터페이스 (외부 API 클라이언트)

```java
public interface PaymentClient {

	/**
	 * 외부 결제사에 결제를 요청하고 처리 결과를 반환한다.
	 *
	 * @param orderId 결제 대상 주문 ID
	 * @param amount  결제 금액
	 * @return 결제사로부터 받은 처리 결과
	 */
	PaymentResult charge(String orderId, long amount);
}
```

#### 구현체 — `@Override` 메서드는 생략

```java
@Component
public class TossPaymentClient implements PaymentClient {

	@Override
	public PaymentResult charge(String orderId, long amount) {
		// 구현 내용
	}
}
```

#### 서비스

```java
@Service
public class OrderService {

	/**
	 * 주문을 생성하고 결제를 요청한 뒤 결과를 반환한다.
	 *
	 * @param request 주문 생성 요청 정보
	 * @return 생성된 주문과 결제 결과가 담긴 응답
	 * @throws PaymentFailedException 결제 처리 실패 시
	 */
	public OrderResponse createOrder(OrderRequest request) throws PaymentFailedException {
		// 구현 내용
	}
}
```

---