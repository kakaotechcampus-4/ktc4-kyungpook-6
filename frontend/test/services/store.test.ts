import { beforeEach, describe, expect, it, vi } from "vitest";
import { confirmStore, updateStore } from "../../src/services/store";
import { patchRequest, postRequest } from "../../src/services/api";

/* 실제 요청은 보내지 않고, 어떤 주소로 무엇을 넘겼는지만 본다. */
vi.mock("../../src/services/api", () => ({
  getRequest: vi.fn(),
  patchRequest: vi.fn(),
  postRequest: vi.fn(),
}));

/**
 * 가게 수정 API 호출 계약.
 *
 * <p>백엔드 StoreController 의 경로와 어긋나면 화면은 멀쩡한데 요청만 조용히 404 가 된다.
 * 경로가 바뀌면 이 테스트가 먼저 깨지도록 고정한다.
 */
describe("updateStore — PATCH /api/stores/{storeId}", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("storeId 를 경로에 넣어 PATCH 로 보낸다", () => {
    updateStore(1, { phone: "053-111-2222" });

    expect(patchRequest).toHaveBeenCalledWith("/api/stores/1", {
      phone: "053-111-2222",
    });
  });

  it("받은 필드를 그대로 본문에 넘긴다 — 프론트가 임의로 필드를 더하지 않는다", () => {
    updateStore(7, { name: "맛나 치킨", status: "SUSPENDED" });

    expect(patchRequest).toHaveBeenCalledWith("/api/stores/7", {
      name: "맛나 치킨",
      status: "SUSPENDED",
    });
  });
});

describe("confirmStore — POST /api/stores/{storeId}/confirm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("confirm 경로로 POST 한다", () => {
    confirmStore(1);

    expect(postRequest).toHaveBeenCalledWith(
      "/api/stores/1/confirm",
      undefined
    );
  });

  it("본문을 보내지 않는다 — 확인 시각은 서버가 정하므로 클라이언트가 넘길 값이 없다", () => {
    confirmStore(1);

    const [, body] = vi.mocked(postRequest).mock.calls[0];
    expect(body).toBeUndefined();
  });
});
