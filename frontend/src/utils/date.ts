/**
 * 화면에 날짜를 찍는 표기를 한곳에 모은다.
 *
 * ko-KR 포맷이 이미 "2026. 08. 20." 모양이라 자체 문자열 조립 대신 Intl을 쓴다.
 * 값이 없거나 파싱되지 않으면 undefined를 돌려주고, "-" 같은 대체 문구는
 * 화면마다 다르므로 호출부가 정한다.
 */

function toDate(value: string | null | undefined): Date | undefined {
  if (!value) return undefined;

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

/** "2026. 05. 12." 표기. Figma 103:147(자체 확인일) */
export function formatDate(value: string | null | undefined) {
  const date = toDate(value);
  if (!date) return undefined;

  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

/** "2026. 08. 20. 18:00" 표기. Figma 112:5450(조사 완료 시각) */
export function formatDateTime(value: string | null | undefined) {
  const date = toDate(value);
  if (!date) return undefined;

  const time = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);

  return `${formatDate(value)} ${time}`;
}
