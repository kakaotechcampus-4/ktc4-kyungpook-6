import { describe, expect, it } from "vitest";
import { findMatchedMenuName, matchesQuery, splitByKeyword } from "../src/utils/childSearch";

const store = {
  name: "행복 닭강정",
  menus: [
    { menuId: 1, name: "닭강정", onSale: true },
    { menuId: 2, name: "치킨마요 컵밥", onSale: true },
  ],
};

describe("matchesQuery", () => {
  it("가게 이름이나 메뉴 이름에 있으면 맞다", () => {
    expect(matchesQuery(store, "행복")).toBe(true);
    expect(matchesQuery(store, "치킨")).toBe(true);
  });
  it("공백·대소문자는 무시한다", () => expect(matchesQuery(store, "행복닭강정")).toBe(true));
  it("어디에도 없으면 틀리다", () => expect(matchesQuery(store, "피자")).toBe(false));
  it("검색어가 비면 모두 맞다", () => expect(matchesQuery(store, "  ")).toBe(true));
});

describe("findMatchedMenuName", () => {
  it("메뉴로 찾았으면 그 메뉴 이름", () => expect(findMatchedMenuName(store, "치킨")).toBe("치킨마요 컵밥"));
  it("가게 이름으로 찾았으면 null", () => expect(findMatchedMenuName(store, "닭강정")).toBeNull());
  it("검색어가 비면 null", () => expect(findMatchedMenuName(store, "")).toBeNull());
});

describe("splitByKeyword", () => {
  it("맞은 부분을 기준으로 나눈다", () => expect(splitByKeyword("동네 치킨집", "치킨")).toEqual(["동네 ", "치킨", "집"]));
  it("대소문자를 무시한다", () => expect(splitByKeyword("BBQ 치킨", "bbq")).toEqual(["", "BBQ", " 치킨"]));
  it("글자 그대로 찾지 못하면 null", () => expect(splitByKeyword("행복 닭강정", "행복닭")).toBeNull());
});
