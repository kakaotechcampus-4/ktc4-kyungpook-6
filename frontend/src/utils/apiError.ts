import { isAxiosError } from 'axios';

/** 응답 본문을 읽지 못했을 때(네트워크 오류 등) 쓸 문구. */
const FALLBACK = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/**
 * 실패 응답에서 담당자에게 보일 문구를 고른다.
 *
 * 백엔드는 에러를 RFC 9457(`application/problem+json`)로 내려주고 `title` 에 한글 문장이
 * 들어 있다(`docs/에러_처리_가이드.md`). 예: "이미 확인한 조사 결과입니다",
 * "반영할 수 있는 수정안이 없습니다". 상태 코드로 문구를 새로 지어내면 백엔드가 구분해 둔
 * 이유가 화면에서 뭉개지므로, 서버가 적어 보낸 말을 그대로 쓴다.
 *
 * 필드 검증 실패(400)면 `errors[0].message` 가 더 구체적이라 그걸 먼저 쓴다.
 */
export function messageOf(error: unknown): string {
  if (!isAxiosError(error)) return FALLBACK;

  const problem = error.response?.data as
    | { title?: string; errors?: { message?: string }[] }
    | undefined;

  return problem?.errors?.[0]?.message ?? problem?.title ?? FALLBACK;
}

/** 로그인이 풀린 응답인가. 401 이면 토큰이 없거나 만료된 것이다. */
export function isUnauthorized(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 401;
}
