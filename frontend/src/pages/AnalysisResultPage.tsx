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
  const { finishedAtLabel, groups, isPending, isError } =
    useAnalysisResultPage();
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

        {isError && (
          <p role="status" className="px-3.5 pt-3.5 text-sm text-[#64748b]">
            조사 결과를 불러오지 못해 목업 데이터를 표시합니다.
          </p>
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
                        onPrimaryAction은 아직 연결하지 않았다.
                        수정 반영·메세지 발송 API가 나오면 훅에 핸들러를 추가한다.
                      */
                      <AgentSurveyResultCard
                        key={task.id}
                        variant={task.classification}
                        storeName={task.storeName}
                        address={task.address}
                        evidences={task.evidences}
                        changes={task.changes}
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
