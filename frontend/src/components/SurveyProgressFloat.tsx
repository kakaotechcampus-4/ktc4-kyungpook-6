import { Icon } from '@iconify/react';
import type { ReactNode } from 'react';
import { FLOAT_ANCHOR, FLOAT_CIRCLE, FLOAT_FOCUS } from './AgentSurveyTrigger';
import {
  SurveyProgressContext,
  useSurveyProgress,
  useSurveyProgressContext,
} from '../hooks/surveyProgress';
import type { SurveyProgressState } from '../hooks/surveyProgress';

/**
 * 조사 상태를 앱 전체에 한 번만 담는다. 라우터 안에 둔다 — 표시를 누르면 결과 화면으로 간다.
 *
 * 로봇 버튼(AgentSurveyTrigger)도 이 값을 봐야 해서 context 로 내린다. 훅을 두 군데서
 * 따로 부르면 "닫았다" 가 둘로 갈려 한쪽만 닫힌다.
 */
export function SurveyProgressProvider({ children }: { children: ReactNode }) {
  const value = useSurveyProgress();

  return (
    <SurveyProgressContext.Provider value={value}>
      {children}
    </SurveyProgressContext.Provider>
  );
}

/**
 * 상태별 원 모양. Figma Frame 228 의 네 칸(작업 중 0% · 작업 중 33% · 작업 완료 · 작업 실패).
 *
 * 색은 화면에 이미 쓰는 값만 쓴다 — 초록 #15803d(영업중 점·반영됨 태그),
 * 빨강 #ef4444(수정 모달 오류), 로봇 갈색 #422006(로봇 버튼).
 */
const CIRCLE: Record<
  SurveyProgressState,
  { background: string; icon: string; iconColor: string }
> = {
  running: {
    /*
      도는 중에는 색이 뒤집힌다 — 어두운 원에 밝은 로봇이다(Figma 115:5633).
      유휴 상태 버튼(AgentSurveyTrigger)과 한눈에 갈리라고 designer 가 반전시켰다.
      보더도 4px -> 6px 로 두꺼워진다. FLOAT_CIRCLE 은 유휴 버튼과 공유하므로 여기서만 덮는다.
    */
    background: 'bg-[#422006] border-[6px]',
    icon: 'mingcute:robot-fill',
    iconColor: 'text-[#FEF08A]',
  },
  done: {
    background: 'bg-[#15803d]',
    icon: 'material-symbols:check',
    iconColor: 'text-white',
  },
  failed: {
    background: 'bg-[#ef4444]',
    icon: 'material-symbols:close-rounded',
    iconColor: 'text-white',
  },
};

/**
 * 진행 링의 둘레. 반지름 25 = 56/2 - 6/2 이라 링이 원의 보더(6px) 자리에 정확히 겹친다.
 * stroke-dasharray 에 둘레를 넣고 dashoffset 으로 남은 만큼을 비워 진행률을 그린다.
 */
const RING_RADIUS = 25;
const RING_LENGTH = 2 * Math.PI * RING_RADIUS;
const RING_WIDTH = 6;

/** 진행 링 색. Figma 115:5637 Green/400. */
const RING_COLOR = '#4ADE80';

/**
 * 조사 진행 상태 표시. 화면 우하단에 떠 있고, 조사가 도는 동안 **모든 화면**에서 보인다.
 *
 * 가게 목록으로 돌아가도 진행 상황이 보여야 의미가 있어서 라우트 안이 아니라 App 에 한 번
 * 둔다. 조사가 없으면 아무것도 그리지 않고, 그 자리는 기존 로봇 버튼(AgentSurveyTrigger)이 쓴다.
 */
function SurveyProgressFloat() {
  const { progress, dismiss, openResult } = useSurveyProgressContext();

  if (!progress) return null;

  const { state, percent, label, description } = progress;
  const circle = CIRCLE[state];
  const isRunning = state === 'running';

  return (
    /*
      상태는 원 모양으로만 알린다 — 글자를 아래에 두지 않는다(Figma 의 "작업 중 0%" 는
      각 상태를 설명하는 캡션이지 화면에 그리는 글자가 아니다).

      그래서 눈에 보이지 않는 사용자를 위한 길을 둘로 나눈다. role="status" 안의 sr-only 는
      상태 이름만 담아 바뀔 때 한 번 읽히고, 진행률은 버튼 이름에 담아 포커스했을 때만
      읽힌다 — 살아 있는 영역에 퍼센트를 넣으면 스크린리더가 3초마다 같은 말을 반복한다.
    */
    <div role="status" className={FLOAT_ANCHOR}>
      <span className="sr-only">{label}</span>

      <div className="relative flex flex-col items-center">
        <button
          type="button"
          onClick={openResult}
          aria-label={
            isRunning
              ? `${description} (${percent}%). 조사 결과 보기`
              : `${description}. 조사 결과 보기`
          }
          title={isRunning ? `${description} (${percent}%)` : description}
          className={`flex items-center rounded-2xl ${FLOAT_FOCUS}`}
        >
          {/* 원 + 진행 링. 링은 원 위에 겹쳐 그린다. */}
          <span className="relative block size-14">
            <span className={`${FLOAT_CIRCLE} ${circle.background}`}>
              <Icon
                icon={circle.icon}
                className={`size-8 shrink-0 ${circle.iconColor}`}
              />
            </span>

            {isRunning && (
              /* -rotate-90: SVG 호는 3시 방향에서 시작한다. 12시에서 시작하도록 돌린다. */
              <svg
                aria-hidden="true"
                viewBox="0 0 56 56"
                className="pointer-events-none absolute inset-0 size-14 -rotate-90"
              >
                <circle
                  cx="28"
                  cy="28"
                  r={RING_RADIUS}
                  fill="none"
                  stroke={RING_COLOR}
                  strokeWidth={RING_WIDTH}
                  strokeLinecap="round"
                  strokeDasharray={RING_LENGTH}
                  strokeDashoffset={RING_LENGTH * (1 - percent / 100)}
                  className="transition-[stroke-dashoffset] duration-500 motion-reduce:transition-none"
                />
              </svg>
            )}
          </span>

        </button>

        {/*
          끝난 조사만 닫을 수 있다. 도는 중에 닫게 두면 진행 상황을 되살릴 길이 없다.
          버튼 안에 버튼을 둘 수 없어 형제로 두고 원의 오른쪽 위에 겹친다.
        */}
        {!isRunning && (
          <button
            type="button"
            onClick={dismiss}
            aria-label={`${label} 알림 닫기`}
            className={`absolute -right-1 -top-1 flex size-6 items-center justify-center rounded-full border border-solid border-[#e2e8f0] bg-white text-[#64748b] shadow-[0px_2px_8px_rgba(15,23,42,0.16)] ${FLOAT_FOCUS}`}
          >
            <Icon
              icon="material-symbols:close-rounded"
              className="size-4 shrink-0"
            />
          </button>
        )}
      </div>
    </div>
  );
}

export default SurveyProgressFloat;
