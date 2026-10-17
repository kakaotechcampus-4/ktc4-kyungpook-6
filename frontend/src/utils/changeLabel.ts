/**
 * 백엔드가 내려주는 원본 값을 화면 글자로 옮긴다.
 *
 * 백엔드 수정안은 `{ field: "status", value: "CLOSED" }` 처럼 **코드 값 그대로** 온다
 * (`ProposedChangeResponse`). 화면에 그대로 쓰면 담당자가 "status: CLOSED" 를 읽게 되므로
 * 여기서 한 번만 옮긴다. 라벨은 `docs/도메인_용어집.md` 의 "화면 라벨" 칸을 따른다.
 *
 * 모르는 값은 **버리지 않고 받은 값을 그대로** 보여준다. 백엔드에 항목이 늘었을 때
 * 화면에서 조용히 사라지면, 담당자는 수정안이 없는 줄 알고 넘어간다.
 */
import type { ChangeField, EvidenceSource } from '../services/analysis';

/** Store 필드명 → 화면 라벨. 용어집의 `Store.name`(가게명) · `Store.phone`(전화번호)을 따른다. */
const FIELD_LABEL: Record<ChangeField, string> = {
  name: '가게명',
  status: '영업 상태',
  phone: '전화번호',
  addressRoad: '주소',
};

/** StoreStatus → 화면 라벨. 목록 뱃지(TableRow)와 같은 말을 쓴다. */
const STATUS_LABEL: Record<string, string> = {
  OPEN: '영업중',
  SUSPENDED: '휴업',
  CLOSED: '폐업',
  UNKNOWN: '미확인',
};

/** 근거 출처 → 화면 라벨. 백엔드가 sourceLabel 을 항상 null 로 주고 화면이 붙이기로 했다. */
const SOURCE_LABEL: Record<EvidenceSource, string> = {
  NTS: '근거 - 국세청 사업자등록 상태',
  AI_WEB: '근거 - AI 웹검색',
};

export function fieldLabel(field: string): string {
  return FIELD_LABEL[field as ChangeField] ?? field;
}

/**
 * 수정안의 새 값을 화면 글자로 옮긴다. 영업 상태만 enum 이고 나머지는 그대로 쓴다.
 * 값이 비어 있으면 "(비움)" 으로 적는다 — 전화번호는 빈 문자열로 지울 수 있어서,
 * 빈칸으로 두면 "무엇으로 바꾸는지 모르는 수정안" 과 구분되지 않는다.
 */
export function changeValueLabel(field: string, value: string | null): string {
  if (value === null || value === '') return '(비움)';
  if (field === 'status') return STATUS_LABEL[value] ?? value;
  return value;
}

export function evidenceSourceLabel(source: string | null): string | undefined {
  if (!source) return undefined;
  return SOURCE_LABEL[source as EvidenceSource] ?? undefined;
}
