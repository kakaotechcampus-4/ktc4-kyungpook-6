import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import Sidebar from '../components/sidebar/Sidebar';
import Skeleton from '../components/ui/Skeleton';
import StoreCandidatePicker from '../components/owner/StoreCandidatePicker';
import { STATUS_TABS, useOwnerApprovalPage } from '../hooks/ownerApproval';
import type { OwnerApplicationResponse } from '../services/owner';
import { formatDateTime } from '../utils/date';

/** 사업자등록번호는 숫자 10자리로 저장된다. 읽기 좋게 끊어 준다. */
function formatBizNo(bizNo: string | null) {
  if (!bizNo) return '-';
  const digits = bizNo.replace(/\D/g, '');
  if (digits.length !== 10) return bizNo;
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
}

/** 휴대폰 번호도 숫자만 저장된다. */
function formatPhone(phone: string | null) {
  if (!phone) return '-';
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 11) {
    return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
  }
  return phone;
}

const FIELD_LABEL = 'font-sans text-xs leading-4 text-[#94a3b8]';
const FIELD_VALUE = 'font-sans text-sm leading-5 text-[#1e293b]';

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className={FIELD_LABEL}>{label}</span>
      <span className={`${FIELD_VALUE} truncate`} title={value}>
        {value}
      </span>
    </div>
  );
}

type AppProps = {
  application: OwnerApplicationResponse;
  isOpen: boolean;
  isProcessing: boolean;
  onToggle: () => void;
  onApprove: (storeId: number) => void;
  onReject: () => void;
};

/**
 * 가입 신청 한 건.
 *
 * 승인 대기만 펼쳐서 처리할 수 있다. 이미 처리한 신청은 되돌릴 방법이 서버에 없어서
 * (거절을 취소하는 API 가 없다) 버튼을 아예 두지 않는다 — 눌러도 409 만 나는 버튼은
 * 담당자에게 "할 수 있다" 는 잘못된 신호다.
 */
function ApplicationCard({
  application,
  isOpen,
  isProcessing,
  onToggle,
  onApprove,
  onReject,
}: AppProps) {
  const pending = application.status === 'PENDING';

  return (
    <article className="flex w-full flex-col gap-3 rounded-lg border border-solid border-[#e2e8f0] p-3">
      <div className="flex w-full items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <h3 className="truncate font-sans text-base font-semibold leading-6 text-black">
            {application.storeName ?? '상호명 없음'}
          </h3>
          <p className="truncate font-sans text-sm leading-5 text-[#64748b]">
            {application.email}
          </p>
        </div>
        <p className="shrink-0 font-sans text-xs leading-4 text-[#94a3b8]">
          신청 {formatDateTime(application.appliedAt) ?? '-'}
        </p>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Field label="대표자" value={application.representativeName ?? '-'} />
        <Field label="사업자등록번호" value={formatBizNo(application.bizNo)} />
        <Field label="휴대폰" value={formatPhone(application.phone)} />
        <Field
          label={application.status === 'PENDING' ? '상태' : '처리 시각'}
          value={
            application.status === 'PENDING'
              ? '승인 대기'
              : `${application.status === 'APPROVED' ? '승인됨' : '거절됨'} · ${
                  formatDateTime(application.reviewedAt) ?? '-'
                }`
          }
        />
      </div>

      {pending ? (
        <div className="flex w-full flex-col gap-2">
          <div className="flex w-full gap-2">
            <button
              type="button"
              onClick={onToggle}
              disabled={isProcessing}
              className="flex h-10 flex-1 items-center justify-center gap-1 rounded-lg bg-[#eab308] font-sans text-sm font-semibold leading-5 text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#eab308] disabled:opacity-50"
            >
              <Icon
                icon={isOpen ? 'lucide:chevron-up' : 'lucide:chevron-down'}
                className="size-4 shrink-0"
              />
              {isOpen ? '접기' : '가게 연결하고 승인하기'}
            </button>
            <button
              type="button"
              onClick={onReject}
              disabled={isProcessing}
              className="flex h-10 w-32 shrink-0 items-center justify-center rounded-lg border border-solid border-[#e2e8f0] font-sans text-sm font-semibold leading-5 text-[#1e293b] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#64748b] disabled:opacity-50"
            >
              거절하기
            </button>
          </div>

          {isOpen ? (
            <StoreCandidatePicker
              memberId={application.memberId}
              isProcessing={isProcessing}
              onApprove={onApprove}
            />
          ) : null}
        </div>
      ) : null}
    </article>
  );
}

type OwnerApprovalPageProps = Omit<ComponentPropsWithoutRef<'div'>, 'children'>;

/**
 * 점주 가입 관리 화면.
 *
 * 백엔드에는 가입 신청 조회·승인·거절·가게 연결 API 가 다 있는데 관리자 웹에서 부르는 곳이
 * 하나도 없었다 — 점주가 가입을 신청해도 승인할 방법이 화면에 없었다(PROMPT-150).
 */
function OwnerApprovalPage({ className, ...props }: OwnerApprovalPageProps) {
  const {
    status,
    setStatus,
    applications,
    isPending,
    isError,
    openedMemberId,
    toggleOpen,
    processingMemberId,
    errorMessage,
    approve,
    reject,
  } = useOwnerApprovalPage();

  const rows = applications?.content ?? [];

  return (
    <div
      className={['flex h-screen w-full items-stretch bg-[#f8fafc]', className]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <Sidebar />

      <main className="flex min-w-px flex-1 flex-col items-start overflow-auto bg-white">
        <div className="flex w-full shrink-0 flex-col items-start gap-1.5 border-b border-solid border-[#e2e8f0] px-4 py-3.5">
          <nav aria-label="현재 위치" className="flex shrink-0 items-center gap-1">
            <p className="whitespace-nowrap font-sans text-xl font-semibold leading-7 text-[#0f172a]">
              선한 영향력 가게
            </p>
            <Icon icon="lucide:chevron-right" className="size-4 shrink-0 text-[#0f172a]" />
            <p className="truncate font-sans text-base font-medium leading-6 text-[#64748b]">
              점주 가입 관리
            </p>
          </nav>
        </div>

        <div className="flex w-full items-center gap-2 px-3.5 pt-3.5">
          {STATUS_TABS.map((tab) => (
            <button
              key={tab.status}
              type="button"
              onClick={() => setStatus(tab.status)}
              aria-pressed={status === tab.status}
              className={[
                'h-9 rounded-lg px-3 font-sans text-sm font-semibold leading-5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#64748b]',
                status === tab.status
                  ? 'bg-[#fef9c3] text-[#a16207]'
                  : 'text-[#64748b] hover:bg-[#f1f5f9]',
              ].join(' ')}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {isError && (
          <p role="alert" className="px-3.5 pt-3.5 text-sm text-[#b91c1c]">
            가입 신청을 불러오지 못했습니다.
          </p>
        )}

        {errorMessage && (
          <p role="alert" className="px-3.5 pt-3.5 text-sm text-[#b91c1c]">
            {errorMessage}
          </p>
        )}

        <div className="flex w-full flex-col items-start gap-3 p-3.5">
          {isPending ? (
            <Skeleton className="h-28 w-full" />
          ) : rows.length === 0 ? (
            <p className="text-sm text-[#64748b]">
              {status === 'PENDING'
                ? '승인을 기다리는 신청이 없습니다.'
                : '해당하는 신청이 없습니다.'}
            </p>
          ) : (
            rows.map((application) => (
              <ApplicationCard
                key={application.memberId}
                application={application}
                isOpen={openedMemberId === application.memberId}
                isProcessing={processingMemberId === application.memberId}
                onToggle={() => toggleOpen(application.memberId)}
                onApprove={(storeId) => approve(application.memberId, storeId)}
                onReject={() => reject(application.memberId)}
              />
            ))
          )}
        </div>
      </main>
    </div>
  );
}

export default OwnerApprovalPage;
