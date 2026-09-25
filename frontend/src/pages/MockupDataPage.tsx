import { Icon } from "@iconify/react";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { ComponentPropsWithoutRef } from "react";
import { getStores } from "../services/store";
import type { StoreResponse } from "../services/store";
import { useStoreListPage } from "../hooks/store";
import { useStoreEditModal } from "../hooks/storeEdit";
import type { StoreEditTarget } from "../hooks/storeEdit";
import Sidebar from "../components/sidebar/Sidebar";
import StoreEditModal from "../components/store/StoreEditModal";
import TableRow from "../components/TableRow";
import AgentSurveyTrigger from "../components/AgentSurveyTrigger";
import AgentSurveyModal from "../components/AgentSurveyModal";
import type { BusinessStatus } from "../components/TableRow";

/** 가게 목록의 한 건. 필드는 TableRow가 받는 props 기준. */
export interface Store {
  id: string;
  name: string;
  phone: string;
  status?: BusinessStatus;
  address: string;
  lastChecked: string;
  /**
   * 수정 모달에 넘길 원본 값. 표기용으로 "-" 를 채운 위 필드들과 달리
   * 서버가 준 값 그대로다. 목업 행에는 없어서 그 행의 연필은 눌리지 않는다.
   */
  editTarget?: StoreEditTarget;
}

function toStore(store: StoreResponse): Store {
  const status = store.status?.toLowerCase();

  return {
    id: String(store.storeId),
    name: store.name,
    phone: store.phone ?? "-",
    status: status === "open" || status === "closed" ? status : undefined,
    address: store.addressRoad ?? "-",
    lastChecked: store.lastCheckedAt?.replace("T", " ") ?? "-",
    editTarget: {
      storeId: store.storeId,
      name: store.name,
      addressRoad: store.addressRoad,
      phone: store.phone,
      status: store.status,
      lastCheckedAt: store.lastCheckedAt,
    },
  };
}

/** 테이블 헤더 셀. Figma 176:92·176:94·176:96·176:98·176:100·176:102 */
const HEADER_CELL = "flex shrink-0 items-center justify-between overflow-clip";

/** 테이블 헤더 텍스트. Pretendard Bold 14/20, white */
const HEADER_TEXT =
  "whitespace-nowrap font-sans text-sm font-bold leading-5 text-white";

/** API 조회 실패 시 표시할 목업 가게 목록. */
const FALLBACK_STORES: Store[] = [
  {
    id: "fallback-1",
    name: "정든 국밥집",
    phone: "053-123-4567",
    status: "open",
    address: "대구 북구 대학로 80",
    lastChecked: "2026-09-01 10:00",
  },
  {
    id: "fallback-2",
    name: "북문 분식",
    phone: "053-234-5678",
    status: "closed",
    address: "대구 북구 대학로 82",
    lastChecked: "2026-08-28 14:30",
  },
];

type MockupDataPageProps = Omit<ComponentPropsWithoutRef<"div">, "children">;

/** 선한 영향력 가게 전체 목록 페이지. Figma Home Page(176:57) */
function MockupDataPage({ className, ...props }: MockupDataPageProps) {
  const [page, setPage] = useState(0);
  const {
    selectedIds, // page를 넘겨도, selectedIds는 유지되도록
    isSelected,
    toggleSelect,
    toggleSelectAll,
    isTriggerVisible,
    isTooltipVisible,
    dismissTooltip,
    surveyTargetCount,
    estimatedMinutes,
    isSurveyModalOpen,
    openSurveyModal,
    closeSurveyModal,
    startSurvey,
  } = useStoreListPage();
  const storeEdit = useStoreEditModal();
  const { data, isPending, isError } = useQuery({
    queryKey: ["stores", { page, limit: 20 }],
    queryFn: () => getStores({ page, limit: 20 }),
    retry: false,
  });
  const stores = isError ? FALLBACK_STORES : data?.content.map(toStore) ?? [];
  /*
    헤더 체크박스 UI 시나리오 (지메일 방식)
    - 범위: 현재 페이지의 가게만 선택·해제한다. 다른 페이지 선택은 건드리지 않는다.
    - 표시: 현재 페이지 기준으로 계산한다.
        현재 페이지 전부 선택 → 체크 / 일부 선택 → 일부 선택 / 하나도 없음 → 빈 칸
      다른 페이지에만 선택이 있으면 이 페이지 헤더는 빈 칸이다.
    - 클릭: 현재 페이지에 선택이 하나라도 있으면(전체·일부) 현재 페이지 해제,
      하나도 없으면 현재 페이지 전체 선택.
    - 조사 대상 수(모달·트리거)는 페이지와 무관하게 전체 선택 수로 센다.
  */
  const selectedCount = stores.filter((store) =>
    selectedIds.has(store.id)
  ).length;
  const allSelected = stores.length > 0 && selectedCount === stores.length;
  /** 일부만 선택된 상태. Figma 111:3911 (파란 배경 + 흰 가로줄) */
  const someSelected = selectedCount > 0 && !allSelected;
  /** 현재 페이지에 선택이 하나라도 있으면 헤더 클릭은 현재 페이지 해제로 동작한다. */
  const hasSelection = allSelected || someSelected;

  return (
    <div
      className={[
        "flex h-screen w-full flex-col items-start bg-[#f8fafc]",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      {...props}
    >
      <div className="flex min-h-px w-full flex-1 items-stretch">
        <Sidebar />

        {/* Main Screen. Figma 176:80 */}
        <main className="flex min-w-px flex-1 flex-col items-start gap-1 overflow-auto bg-white py-4">
          {/* Breadcrumb. Figma 176:81~176:87 */}
          <div className="flex w-full shrink-0 flex-col items-start gap-1.5 px-4">
            <nav
              aria-label="현재 위치"
              className="flex h-7 w-full items-center overflow-clip"
            >
              <div className="flex shrink-0 items-center justify-center gap-1">
                <p className="shrink-0 whitespace-nowrap font-sans text-xl font-semibold leading-7 text-[#0f172a]">
                  선한 영향력 가게
                </p>
                <Icon
                  icon="lucide:chevron-right"
                  className="size-4 shrink-0 text-[#0f172a]"
                />
                <p className="shrink-0 truncate font-sans text-base font-medium leading-6 text-[#64748b]">
                  전체 목록
                </p>
              </div>
            </nav>
          </div>

          {isError && (
            <p role="status" className="px-4 py-2 text-sm text-[#64748b]">
              가게 정보를 불러오지 못해 목업 데이터를 표시합니다.
            </p>
          )}

          {/*
            테이블. Figma 176:88
            Figma의 1152px은 Main Screen 폭과 같은 값(= 꽉 찬 상태)이라 w-full로 둔다.
            컬럼 폭이 고정값(256·128·24)이라 1152 아래로는 줄이지 않고 가로 스크롤한다.
          */}
          <div className="flex w-full min-w-[1152px] shrink-0 flex-col items-start">
            {/* 헤더 행 래퍼. Figma 176:89 */}
            <div className="flex w-full shrink-0 flex-col items-start overflow-clip pb-0.5 pt-3">
              {/* 테이블 헤더. Figma 176:90 */}
              <div className="flex h-[38px] w-full shrink-0 items-center gap-2.5 overflow-clip border border-solid border-[#eab308] bg-[#eab308] p-2">
                {/*
                  전체 선택 체크박스.
                  미선택 = Figma 176:91 (흰 배경, 보더 없음)
                  선택됨 = Figma 111:3911 (Blue/500 + 흰 가로줄)
                  일부 선택이든 전체 선택이든 가로줄로 통일한다.
                  화면은 동일하지만 checked / indeterminate 값은 실제 상태를 유지해
                  스크린리더가 "체크됨"과 "일부 선택됨"을 구분해 읽을 수 있게 둔다.
                */}
                <label className="relative shrink-0">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    ref={(element) => {
                      if (element) element.indeterminate = someSelected;
                    }}
                    onChange={() =>
                      toggleSelectAll(
                        stores.map((store) => store.id),
                        !hasSelection
                      )
                    }
                    className="peer sr-only"
                    aria-label="전체 선택"
                  />
                  <span
                    className={[
                      "flex size-5 items-center justify-center overflow-clip rounded-[2px] peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-white",
                      allSelected || someSelected ? "bg-[#3b82f6]" : "bg-white",
                    ].join(" ")}
                  >
                    {hasSelection ? (
                      <Icon
                        icon="material-symbols:horizontal-rule"
                        className="size-4 shrink-0 text-white"
                      />
                    ) : null}
                  </span>
                </label>

                <div className={`${HEADER_CELL} min-w-px flex-1`}>
                  <p className={HEADER_TEXT}>상호명</p>
                </div>
                <div className={`${HEADER_CELL} w-64`}>
                  <p className={HEADER_TEXT}>전화번호</p>
                </div>
                <div className={`${HEADER_CELL} w-32`}>
                  <p className={HEADER_TEXT}>현재 영업 상태</p>
                </div>
                <div className={`${HEADER_CELL} w-64`}>
                  <p className={HEADER_TEXT}>주소</p>
                </div>
                <div className={`${HEADER_CELL} w-64`}>
                  <p className={HEADER_TEXT}>마지막 확인일</p>
                </div>
                <div className={`${HEADER_CELL} w-6`}>
                  <p className={HEADER_TEXT}>수정</p>
                </div>
              </div>
            </div>

            {/* 데이터 행. Figma 176:104 이하 반복 */}
            {isPending && (
              <div role="status" className="w-full">
                <span className="sr-only">가게 목록을 불러오는 중입니다.</span>
                {Array.from({ length: 5 }, (_, index) => (
                  <TableRow key={index} isLoading />
                ))}
              </div>
            )}
            {!isPending && stores.length === 0 && (
              <p role="status" className="px-4 py-6 text-sm text-[#64748b]">
                표시할 가게가 없습니다.
              </p>
            )}
            {stores.map((store) => {
              /* 목업 행은 서버에 없는 가게라 수정할 대상이 없다. */
              const { editTarget } = store;

              return (
                <TableRow
                  key={store.id}
                  name={store.name}
                  phone={store.phone}
                  status={store.status}
                  address={store.address}
                  lastCheckedAt={store.lastChecked}
                  checked={isSelected(store.id)}
                  onCheckedChange={(checked) => toggleSelect(store.id, checked)}
                  onEdit={
                    editTarget ? () => storeEdit.open(editTarget) : undefined
                  }
                />
              );
            })}
          </div>
          {!isError && data && data.totalPages > 0 && (
            <nav
              aria-label="가게 목록 페이지"
              className="flex items-center gap-4 px-4 py-3 text-sm"
            >
              <button
                type="button"
                disabled={data.page === 0}
                onClick={() => setPage(data.page - 1)}
                className="disabled:opacity-40"
              >
                이전
              </button>
              <span>
                {data.page + 1} / {data.totalPages}
              </span>
              <button
                type="button"
                disabled={!data.hasNext}
                onClick={() => setPage(data.page + 1)}
                className="disabled:opacity-40"
              >
                다음
              </button>
            </nav>
          )}
        </main>
      </div>

      {/*
        가게를 하나라도 선택하면 뜨는 조사 트리거. Figma 111:2643 / 115:5592
        position: fixed라 이 위치에 두어도 화면 우하단에 고정된다.
      */}
      <AgentSurveyTrigger
        visible={isTriggerVisible}
        showTooltip={isTooltipVisible}
        onButtonClick={openSurveyModal}
        onDismissTooltip={dismissTooltip}
      />

      {/* 조사 시작 확인 모달. Figma Modal(111:3779) */}
      <AgentSurveyModal
        open={isSurveyModalOpen}
        storeCount={surveyTargetCount}
        estimatedMinutes={estimatedMinutes}
        onStart={startSurvey}
        onCancel={closeSurveyModal}
      />

      {/* 가게 정보 수정 모달. Figma Modal(102:4996) */}
      <StoreEditModal
        open={storeEdit.isOpen}
        values={storeEdit.values}
        onChange={storeEdit.setValue}
        lastCheckedAtLabel={storeEdit.lastCheckedAtLabel}
        onSave={storeEdit.save}
        onConfirm={storeEdit.confirm}
        onClose={storeEdit.close}
        isSaving={storeEdit.isSaving}
        isConfirming={storeEdit.isConfirming}
        errorMessage={storeEdit.errorMessage}
      />
    </div>
  );
}

export default MockupDataPage;
