import { useQuery } from '@tanstack/react-query';
import { getMe } from '../services/auth';
import { getAccessToken } from '../services/authToken';

export type UseCurrentUserResult = {
  /** 사이드바 푸터에 찍을 이름. 로그인 전이거나 조회에 실패하면 undefined. */
  userName: string | undefined;
  /** 조회 중 여부. Skeleton 노출에 쓰인다. */
  isUserLoading: boolean;
};

/**
 * 로그인 사용자. 사이드바가 직접 부른다.
 *
 * 서버에는 이름 칸이 없고 이메일만 있어서 **이메일의 @ 앞부분**을 이름 자리에 쓴다.
 * 이름 칸이 생기면 여기만 고치면 된다.
 *
 * 토큰이 없으면 아예 부르지 않는다 — 로그인 화면에서도 사이드바가 뜨는 건 아니지만,
 * 토큰 없이 부르면 401 이 나고 응답 인터셉터가 로그인 화면으로 보내 버린다.
 */
export function useCurrentUser(): UseCurrentUserResult {
  const { data, isPending } = useQuery({
    queryKey: ['me'],
    queryFn: getMe,
    enabled: Boolean(getAccessToken()),
    retry: false,
    staleTime: 5 * 60 * 1000,
  });

  return {
    userName: data?.email?.split('@')[0],
    isUserLoading: Boolean(getAccessToken()) && isPending,
  };
}
