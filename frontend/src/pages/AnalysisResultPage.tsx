import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import Sidebar from '../components/sidebar/Sidebar';
import Skeleton from '../components/ui/Skeleton';
import AgentSurveyResultCard from '../components/AgentSurveyResultCard';
import AnalysisResultSection from '../components/analysis/AnalysisResultSection';
import StoreEditModal from '../components/store/StoreEditModal';
import type { TaskClassification } from '../components/AgentSurveyResultCard';
import { useAnalysisResultPage } from '../hooks/analysis';
import { useStoreEditModal } from '../hooks/storeEdit';

/** 섹션 순서와 제목. Figma 112:5453 / 112:5500 / 112:5526 */
const SECTIONS: { classification: TaskClassification; title: string }[] = [
  { classification: 'priority', title: '우선 확인이 필요한 가게' },
  { classification: 'additional', title: '추가 확인이 필요한 가게' },
  { classification: 'unchanged', title: '변화 근거가 없는 가게' },
  /*
    조사 자체가 실패한 가게. "변화 근거가 없는 가게"와 묶여 있었는데,
    변화가 없어서 넘어가도 되는 가게와 아직 아무도 못 본 가게가 한 칸에 섞였다(PROMPT-150).
  */
  { classification: 'failed', title: '조사하지 못한 가게' },
];

/** 조회 중에 섹션마다 깔아둘 카드 자리 수. */
const SKELETON_CARDS = [0, 1];

type AnalysisResultPageProps = Omit<
  ComponentPropsWithoutRef<'div'>,
  'children'
>;

/**
 * 에이전트 조사 결과 페이지. Figma MacBook Air - 18(112:4993)
 *
 * 라우트에 바로 걸리는 페이지라 className 외의 props를 받지 않고
 * useAnalysisResultPage로 필요한 데이터를 스스로 불러온다.
 */
function AnalysisResultPage({ className, ...props }: AnalysisResultPageProps) {
  const {
    finishedAtLabel,
    groups,
    isPending,
    isError,
    noJobYet,
    progressLabel,
    processingTaskId,
    actionErrorMessage,
    applyChanges,
    requestOneCall,
    callTargetCount,
    requestAllCalls,
    isRequestingAllCalls,
    bulkCallMessage,
  } = useAnalysisResultPage();
  const storeEdit = useStoreEditModal();

  return (
    <div
      className={[
        'flex h-screen w-full items-stretch bg-[#f8fafc]',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <Sidebar />

      {/* Frame 41. Figma 112:5015 */}
      <main className="flex min-w-px flex-1 flex-col items-start overflow-auto bg-white">
        {/* 브레드크럼. Figma 112:5016 */}
        <div className="flex w-full shrink-0 flex-col items-start gap-1.5 border-b border-solid border-[#e2e8f0] px-4 py-3.5">
          <nav
            aria-label="현재 위치"
            className="flex shrink-0 items-center gap-1"
          >
            <p className="shrink-0 whitespace-nowrap font-sans text-xl font-semibold leading-7 text-[#0f172a]">
              선한 영향력 가게
            </p>
            <Icon
              icon="lucide:chevron-right"
              className="size-4 shrink-0 text-[#0f172a]"
            />
            <p className="shrink-0 truncate font-sans text-base font-medium leading-6 text-[#64748b]">
              분석 결과
            </p>
            <Icon
              icon="lucide:chevron-right"
              className="size-4 shrink-0 text-[#0f172a]"
            />
            {/* 조사 완료 시각만 API 값이라 이 텍스트 노드에만 Skeleton을 쓴다. */}
            <p className="shrink-0 truncate font-sans text-base font-normal leading-6 text-[#64748b]">
              {isPending ? <Skeleton className="h-5 w-36" /> : finishedAtLabel}
            </p>
          </nav>
        </div>

        {noJobYet && (
          <p role="status" className="px-3.5 pt-3.5 text-sm text-[#64748b]">
            아직 조사한 기록이 없습니다. 가게 목록에서 조사를 시작해 주세요.
          </p>
        )}

        {isError && (
          <p role="status" className="px-3.5 pt-3.5 text-sm text-[#64748b]">
            조사 결과를 불러오지 못해 목업 데이터를 표시합니다.
          </p>
        )}

        {progressLabel && (
          <p role="status" className="px-3.5 pt-3.5 text-sm text-[#475569]">
            {progressLabel}
          </p>
        )}

        {actionErrorMessage && (
          <p role="alert" className="px-3.5 pt-3.5 text-sm text-[#b91c1c]">
            {actionErrorMessage}
          </p>
        )}

        {bulkCallMessage && (
          <p role="status" className="px-3.5 pt-3.5 text-sm text-[#1d4ed8]">
            {bulkCallMessage}
          </p>
        )}

        {/*
          전화 대상이 하나도 없으면 내린다 — 눌러도 "0곳에 넣었습니다" 만 나오는 버튼이다.
          대기열은 전화 API 가 갖고 있어서 한 번 누르면 차례대로 걸린다. 화면이 한 통씩
          기다리며 다음을 넣지 않는다.
        */}
        {callTargetCount > 0 && (
          <div className="flex w-full shrink-0 items-center gap-2 px-3.5 pt-3.5">
            <button
              type="button"
              onClick={requestAllCalls}
              disabled={isRequestingAllCalls}
              className="flex h-10 shrink-0 items-center justify-center gap-1 rounded-lg border border-solid border-[#3b82f6] px-3 font-sans text-sm font-semibold leading-5 text-[#3b82f6] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6] disabled:opacity-60"
            >
              <Icon icon="material-symbols:call" className="size-4 shrink-0" />
              전화 대상 {callTargetCount}곳에 한 번에 전화 걸기
            </button>
          </div>
        )}

        {/* Funnel Analysis Window. Figma 112:5452 */}
        <div className="flex w-full flex-col items-start gap-3 p-3.5">
          {SECTIONS.map(({ classification, title }) => {
            const tasks = groups[classification];

            /*
              빈 섹션은 아예 내리지 않는다. 대응하는 Figma 노드가 없어
              "없습니다" 같은 문구를 새로 만들게 되는데, 조사 결과 화면에서
              해당 분류가 0건인 건 알려야 할 일이 아니라 알릴 게 없는 상태다.
            */
            if (!isPending && tasks.length === 0) return null;

            return (
              <AnalysisResultSection
                key={classification}
                title={title}
                count={tasks.length}
                isLoading={isPending}
              >
                {isPending
                  ? SKELETON_CARDS.map((card) => (
                      <AgentSurveyResultCard
                        key={card}
                        variant={classification}
                        isLoading
                      />
                    ))
                  : tasks.map((task) => (
                      /*
                        주 버튼("즉시 수정 반영하기")은 우선확인에만 있다. 추가확인에서 할 일은
                        가게에 연락하는 것 하나라, 그 분류의 행동은 "전화로 확인하기" 가 맡는다.
                      */
                      <AgentSurveyResultCard
                        key={task.id}
                        variant={task.classification}
                        storeName={task.storeName}
                        address={task.address}
                        evidences={task.evidences}
                        changes={task.changes}
                        failureReason={task.failureReason}
                        verifiedLabel={task.verifiedLabel}
                        callStatus={task.callStatus}
                        isProcessing={processingTaskId === task.taskId}
                        onPrimaryAction={
                          task.classification === 'priority'
                            ? () => applyChanges(task.taskId)
                            : undefined
                        }
                        onCall={
                          task.canCall
                            ? () => requestOneCall(task.taskId)
                            : undefined
                        }
                        onEdit={() =>
                          storeEdit.open({
                            storeId: task.storeId,
                            name: task.storeName,
                            addressRoad: task.addressRoad,
                          })
                        }
                      />
                    ))}
              </AnalysisResultSection>
            );
          })}
        </div>
      </main>

      {/*
        가게 정보 수정 모달. Figma 102:4996
        조사 결과 응답에는 전화번호·운영 상태·확인일이 없어 빈 칸으로 열린다.
        부분 수정이라 담당자가 건드리지 않은 칸은 보내지 않으므로 기존 값이 지워지지 않는다.
        Task 응답에 가게 스냅샷이 실리면 open()에 그 값을 함께 넘기면 된다.
      */}
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

export default AnalysisResultPage;
