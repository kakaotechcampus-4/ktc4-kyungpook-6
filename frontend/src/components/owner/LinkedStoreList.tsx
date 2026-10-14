import { useLinkedStores } from '../../hooks/ownerApproval';
import { formatDateTime } from '../../utils/date';
import Skeleton from '../ui/Skeleton';

type LinkedStoreListProps = {
  memberId: number;
  /** 처리 요청이 날아가 있는 동안 true. 버튼을 잠근다. */
  isProcessing: boolean;
  onDetach: (storeId: number) => void;
};

/**
 * 점주에게 지금 연결된 가게와 연결을 끊는 자리.
 *
 * 연결을 끊는 API 는 전부터 있었지만 **무엇이 연결돼 있는지 볼 방법이 없어** 끊을 대상을
 * 고를 수 없었다(PROMPT-150). 끊어도 기록은 남고 체크인 권한만 사라진다.
 */
function LinkedStoreList({ memberId, isProcessing, onDetach }: LinkedStoreListProps) {
  const { data: stores, isPending, isError } = useLinkedStores(memberId);

  if (isPending) return <Skeleton className="h-14 w-full" />;

  if (isError) {
    return (
      <p role="alert" className="text-sm text-[#b91c1c]">
        연결된 가게를 불러오지 못했습니다.
      </p>
    );
  }

  if (!stores || stores.length === 0) {
    return (
      <p className="text-sm text-[#64748b]">
        연결된 가게가 없습니다. 이 계정은 어느 가게에도 체크인할 수 없습니다.
      </p>
    );
  }

  return (
    <div className="flex w-full flex-col items-start gap-2">
      <p className="text-sm font-medium leading-5 text-[#475569]">
        연결된 가게 ({stores.length}곳)
      </p>

      <ul className="flex w-full flex-col gap-2">
        {stores.map((store) => (
          <li
            key={store.storeId}
            className="flex w-full items-center gap-2 rounded-lg border border-solid border-[#e2e8f0] p-2.5"
          >
            <span className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="truncate font-sans text-sm font-semibold leading-5 text-[#0f172a]">
                {store.name}
              </span>
              <span className="truncate font-sans text-xs leading-4 text-[#64748b]">
                {store.addressRoad ?? '주소 없음'} · 연결{' '}
                {formatDateTime(store.linkedAt) ?? '-'}
              </span>
            </span>
            <button
              type="button"
              disabled={isProcessing}
              onClick={() => onDetach(store.storeId)}
              className="h-9 shrink-0 rounded-lg border border-solid border-[#e2e8f0] px-3 font-sans text-sm font-semibold leading-5 text-[#1e293b] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#64748b] disabled:opacity-50"
            >
              연결 끊기
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default LinkedStoreList;
