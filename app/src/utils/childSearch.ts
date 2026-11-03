import type { ChildStore } from "../services/childStore";

/** 검색 비교용. 공백을 지우고 소문자로. */
const normalize = (text: string) => text.replace(/\s/g, "").toLowerCase();

/** 공백·대소문자를 무시하고 text 에 검색어가 들어 있는지. 검색어가 비면 true. */
export const includesKeyword = (text: string, query: string) => normalize(text).includes(normalize(query));

/** 가게 이름이나 메뉴 이름에 검색어가 들어 있는지. 가게 목록과 홈 지도가 같이 쓴다. */
export const matchesQuery = (store: Pick<ChildStore, "name" | "menus">, query: string) =>
  [store.name, ...store.menus.map((menu) => menu.name)].some((text) => includesKeyword(text, query));

/**
 * 가게 이름이 아니라 메뉴로 찾은 경우 그 메뉴 이름. 검색 결과에 "치킨마요 컵밥 · 주소" 처럼 보여 준다.
 * 이름으로 찾았거나 검색어가 비었으면 null.
 */
export const findMatchedMenuName = (store: Pick<ChildStore, "name" | "menus">, query: string) => {
  if (normalize(query) === "" || includesKeyword(store.name, query)) return null;
  return store.menus.find((menu) => includesKeyword(menu.name, query))?.name ?? null;
};

/**
 * 검색어에 맞는 부분을 강조하려고 [앞, 맞은 부분, 뒤] 로 나눈다. 대소문자는 무시한다.
 * 검색어에 공백이 섞여 글자 그대로는 찾지 못하면 null — 강조 없이 그대로 보여 준다.
 */
export const splitByKeyword = (text: string, query: string): [string, string, string] | null => {
  const keyword = query.trim();
  if (keyword === "") return null;
  const index = text.toLowerCase().indexOf(keyword.toLowerCase());
  if (index < 0) return null;
  return [text.slice(0, index), text.slice(index, index + keyword.length), text.slice(index + keyword.length)];
};
