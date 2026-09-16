export type UseCurrentUserResult = {
  /** 로그인 사용자 이름. Sidebar 푸터에 쓴다. */
  userName: string | undefined;
  /** 사용자 정보 조회 중 여부. Sidebar의 Skeleton 노출 여부에 쓰인다. */
  isUserLoading: boolean;
};

/**
 * 로그인 사용자. 페이지마다 Sidebar에 같은 값을 넘겨야 해서 훅으로 뺀다.
 *
 * 조회 API가 아직 없어 지금은 빈 값을 돌려준다.
 * TODO: API가 나오면 여기서 useQuery로 조회한다. 호출부는 바꿀 필요가 없다.
 */
export function useCurrentUser(): UseCurrentUserResult {
  return { userName: undefined, isUserLoading: false };
}
