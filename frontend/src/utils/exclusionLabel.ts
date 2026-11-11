/**
 * 조사 제외 사유의 화면 문구. **사유별 한국어는 이 파일에만 둔다.**
 *
 * 백엔드는 `GET /api/stores` 의 가게마다 `investigationExcluded`(빠지는지)와
 * `investigationExclusionReason`(왜)을 코드 값 그대로 내려준다. 판정은
 * `InvestigationTargetSelector.exclusionReason` 한 곳에서만 하고, 화면은 그 값을 문구로 옮기기만 한다 —
 * `lastCheckedAt` 이 비었는지 같은 값으로 프론트가 다시 판정하면, 목록이 미리 알려 준 제외와 조사가
 * 실제로 빼는 가게가 어긋난다(에러 응답의 `type` 을 화면 문구로 옮기는 것과 같은 방향).
 *
 * 문구가 두 벌인 이유는 들어가는 자리가 둘이고 조사가 다르기 때문이다.
 * - `REASON_LABEL`: 목록 행의 표시·툴팁에 쓰는 **명사구** ("국세청 대조 기록 없음")
 * - `REASON_CAUSE`: 조사 시작 모달 한 줄에 쓰는 **원인절** ("…국세청 대조 기록이 없어 조사되지 않습니다")
 * 한 벌로 합치면 어느 한쪽이 어색해진다.
 *
 * 모르는 값은 **버리지 않고 받은 값을 그대로** 보여준다(utils/changeLabel 과 같은 규칙).
 * 백엔드에 사유가 늘었을 때 화면에서 조용히 사라지면, 담당자는 그 가게가 조사된 줄 알고 넘어간다.
 */
import type { InvestigationExclusionReason } from '../services/store';

/** 목록 행에 붙는 명사구. Record 로 두어 백엔드 enum 에 값이 늘면 타입 에러가 나게 한다. */
const REASON_LABEL: Record<InvestigationExclusionReason, string> = {
  NO_NTS_CHECK: '국세청 대조 기록 없음',
  DATA_PROBLEM: '사업자등록번호 없음·오류',
  ALREADY_CLOSED: '폐업 확인됨',
  ALREADY_SUSPENDED: '휴업 확인됨',
  STORE_NOT_FOUND: '없는 가게',
};

/** 모달 한 줄에 쓰는 원인절. "이 중 N곳은 " + 이 값 + " 조사되지 않습니다" 로 이어진다. */
const REASON_CAUSE: Record<InvestigationExclusionReason, string> = {
  NO_NTS_CHECK: '국세청 대조 기록이 없어',
  DATA_PROBLEM: '사업자등록번호가 없거나 국세청에 없는 번호라',
  ALREADY_CLOSED: '우리와 국세청 모두 폐업으로 확인돼',
  ALREADY_SUSPENDED: '우리와 국세청 모두 휴업으로 확인돼',
  STORE_NOT_FOUND: '목록에 없는 가게라',
};

/**
 * 사유를 목록 행에 쓸 문구로 옮긴다. 빠지지 않는 가게(null)면 undefined.
 *
 * @example
 * exclusionReasonLabel('NO_NTS_CHECK'); // '국세청 대조 기록 없음'
 */
export function exclusionReasonLabel(
  reason: string | null | undefined,
): string | undefined {
  if (!reason) return undefined;
  return REASON_LABEL[reason as InvestigationExclusionReason] ?? reason;
}

/** 행에 마우스를 올렸을 때 읽는 한 줄. 왜 빠지는지까지 적는다. */
export function exclusionReasonTitle(
  reason: string | null | undefined,
): string | undefined {
  const label = exclusionReasonLabel(reason);
  if (!label) return undefined;
  return `${label} — 지금 조사를 시작하면 이 가게는 조사되지 않습니다`;
}

/**
 * 고른 가게들의 사유를 세어 조사 시작 모달에 띄울 줄을 만든다.
 *
 * **빠지는 가게가 없으면 빈 배열이다** — 부르는 쪽은 줄을 아예 그리지 않는다. "0곳은 빠집니다" 는
 * 알려 줄 것이 없는데 자리만 차지하고, 담당자에게 무언가 잘못됐다는 인상을 준다.
 *
 * 사유가 여러 가지면 사유마다 한 줄씩, **많은 사유부터** 적는다. 같은 수면 사유 이름 순으로 고정한다 —
 * 선택 순서에 따라 줄 순서가 바뀌면 같은 선택을 다시 열었을 때 화면이 달라 보인다.
 *
 * @param reasons 고른 가게들의 사유. 빠지지 않는 가게는 null 로 섞여 들어온다
 * @example
 * exclusionNotices(['NO_NTS_CHECK', null, 'NO_NTS_CHECK']);
 * // ['이 중 2곳은 국세청 대조 기록이 없어 조사되지 않습니다']
 */
export function exclusionNotices(
  reasons: readonly (string | null | undefined)[],
): string[] {
  const counts = new Map<string, number>();
  reasons.forEach((reason) => {
    if (!reason) return;
    counts.set(reason, (counts.get(reason) ?? 0) + 1);
  });

  return [...counts.entries()]
    .sort(([leftReason, left], [rightReason, right]) =>
      right - left || leftReason.localeCompare(rightReason),
    )
    .map(([reason, count]) => {
      const cause =
        REASON_CAUSE[reason as InvestigationExclusionReason] ?? `${reason} 이라`;
      return `이 중 ${count}곳은 ${cause} 조사되지 않습니다`;
    });
}
