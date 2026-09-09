import { Icon } from '@iconify/react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ComponentPropsWithoutRef } from 'react';
import { getStores } from '../services/store';
import type { StoreResponse } from '../services/store';
import Sidebar from '../components/sidebar/Sidebar';
import type { SidebarNavKey } from '../components/sidebar/Sidebar';
import TableRow from '../components/TableRow';
import type { BusinessStatus } from '../components/TableRow';

/** 가게 목록의 한 건. 필드는 TableRow가 받는 props 기준. */
export interface Store {
  id: string;
  name: string;
  phone: string;
  status?: BusinessStatus;
  address: string;
  lastChecked: string;
}

function toStore(store: StoreResponse): Store {
  const status = store.status?.toLowerCase();

  return {
    id: String(store.storeId),
    name: store.name,
    phone: store.phone ?? '-',
    status: status === 'open' || status === 'closed' ? status : undefined,
    address: store.addressRoad ?? '-',
    lastChecked: store.lastCheckedAt?.replace('T', ' ') ?? '-',
  };
}

/** 테이블 헤더 셀. Figma 176:92·176:94·176:96·176:98·176:100·176:102 */
const HEADER_CELL = 'flex shrink-0 items-center justify-between overflow-clip';

/** 테이블 헤더 텍스트. Pretendard Bold 14/20, white */
const HEADER_TEXT =
  "whitespace-nowrap font-sans text-sm font-bold leading-5 text-white";

type MockupDataPageProps = {
  /** API 조회 실패 시 표시할 기존 목업 가게 목록. */
  stores: Store[];
  /** 선택된 가게 id 목록 */
  selectedIds?: string[];
  onSelectStore?: (id: string, checked: boolean) => void;
  onSelectAll?: (checked: boolean) => void;
  onEditStore?: (id: string) => void;
  activeNavKey?: SidebarNavKey;
  onNavigate?: (key: SidebarNavKey) => void;
  userName?: string;
  isUserLoading?: boolean;
} & Omit<ComponentPropsWithoutRef<'div'>, 'children'>;

/** 선한 영향력 가게 전체 목록 페이지. Figma Home Page(176:57) */
function MockupDataPage({
  stores: mockStores,
  selectedIds = [],
  onSelectStore,
  onSelectAll,
  onEditStore,
  activeNavKey = 'stores',
  onNavigate,
  userName,
  isUserLoading = false,
  className,
  ...props
}: MockupDataPageProps) {
  const [page, setPage] = useState(0);
  const { data, isPending, isError } = useQuery({
    queryKey: ['stores', { page, limit: 20 }],
    queryFn: () => getStores({ page, limit: 20 }),
    retry: false,
  });
  const stores = isError ? mockStores : (data?.content.map(toStore) ?? []);
  const selectedCount = stores.filter((store) => selectedIds.includes(store.id)).length;
  const allSelected = stores.length > 0 && selectedCount === stores.length;
  /** 일부만 선택된 상태. Figma 111:3911 (파란 배경 + 흰 가로줄) */
  const someSelected = selectedCount > 0 && !allSelected;
  /** 선택된 게 하나라도 있으면 헤더 클릭은 전체 해제로 동작한다. */
  const hasSelection = allSelected || someSelected;

  return (
    <div
      className={[
        'flex h-screen w-full flex-col items-start bg-[#f8fafc]',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <div className="flex min-h-px w-full flex-1 items-stretch">
        <Sidebar
          activeKey={activeNavKey}
          onNavigate={onNavigate}
          userName={userName}
          isUserLoading={isUserLoading}
        />

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
                    onChange={() => onSelectAll?.(!hasSelection)}
                    className="peer sr-only"
                    aria-label="전체 선택"
                  />
                  <span
                    className={[
                      'flex size-5 items-center justify-center overflow-clip rounded-[2px] peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-white',
                      allSelected || someSelected
                        ? 'bg-[#3b82f6]'
                        : 'bg-white',
                    ].join(' ')}
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
            {stores.map((store) => (
              <TableRow
                key={store.id}
                name={store.name}
                phone={store.phone}
                status={store.status}
                address={store.address}
                lastCheckedAt={store.lastChecked}
                checked={selectedIds.includes(store.id)}
                onCheckedChange={(checked) => onSelectStore?.(store.id, checked)}
                onEdit={() => onEditStore?.(store.id)}
              />
            ))}
          </div>
          {!isError && data && data.totalPages > 0 && (
            <nav aria-label="가게 목록 페이지" className="flex items-center gap-4 px-4 py-3 text-sm">
              <button
                type="button"
                disabled={data.page === 0}
                onClick={() => setPage(data.page - 1)}
                className="disabled:opacity-40"
              >
                이전
              </button>
              <span>{data.page + 1} / {data.totalPages}</span>
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
    </div>
  );
}

export default MockupDataPage;
