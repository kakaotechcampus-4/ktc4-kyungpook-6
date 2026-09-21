import { Icon } from '@iconify/react';
import type { ComponentPropsWithoutRef } from 'react';
import tooltipArrow from '../assets/tooltip-arrow.svg';

type AgentSurveyTriggerProps = {
  /** 선택된 가게가 하나도 없으면 false. false면 아무것도 렌더링하지 않는다. */
  visible: boolean;
  /** 말풍선 노출 여부. 닫기(X)를 누른 뒤에는 false가 되어 로봇 버튼만 남는다. */
  showTooltip: boolean;
  /** 로봇 버튼 클릭. 조사 시작 모달을 여는 용도. */
  onButtonClick: () => void;
  /** 말풍선 닫기(X) 클릭. */
  onDismissTooltip: () => void;
} & Omit<ComponentPropsWithoutRef<'div'>, 'children'>;

/**
 * 가게를 하나 이상 선택했을 때 화면 우하단에 뜨는 조사 트리거.
 * 로봇 버튼(Figma 111:2643)과 말풍선(Figma 115:5592)으로 이루어진다.
 *
 * 우하단 고정 오프셋 16px은 Figma의 1280x832 아트보드 기준으로 계산한 값이다.
 * (로봇 버튼 우측 끝 1264 / 아래쪽 끝 816)
 */
function AgentSurveyTrigger({
  visible,
  showTooltip,
  onButtonClick,
  onDismissTooltip,
  className,
  ...props
}: AgentSurveyTriggerProps) {
  if (!visible) return null;

  return (
    <div
      className={[
        'fixed bottom-4 right-4 z-40 flex flex-col items-end',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {showTooltip && (
        /*
          말풍선. Figma 115:5593 (Blue/500, rounded-lg 8px, padding 14px, 폭 338px)
          mb-5(20px)는 아래로 삐져나온 꼬리 17px + 로봇 버튼과의 여백 3px이다.
        */
        <div className="relative mb-5 w-[338px] shrink-0 rounded-lg bg-[#3b82f6] p-3.5">
          {/* 제목 + 닫기 버튼 행. Figma 115:5594 */}
          <div className="flex items-start justify-between gap-2">
            <p className="shrink-0 whitespace-nowrap font-sans text-lg font-bold leading-7 text-white">
              선택한 가게들을 같이 확인해봐요!
            </p>
            {/* 말풍선 본문에는 클릭 동작이 없고, 닫기 버튼만 핸들러를 가진다. */}
            <button
              type="button"
              onClick={onDismissTooltip}
              aria-label="안내 말풍선 닫기"
              className="shrink-0 overflow-clip rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
            >
              <Icon
                icon="material-symbols:close-rounded"
                className="size-5 shrink-0 text-white"
              />
            </button>
          </div>

          {/* 본문. Figma 115:5598 (Pretendard Regular 16/24) */}
          <p className="mt-0.5 w-full font-sans text-base font-normal leading-6 text-white">
            에이전트가 가게들의 정보를 직접 조사하고 비교하여 검토가 필요한
            가게를 찾아줘요.
          </p>

          {/*
            말풍선 꼬리. Figma 115:5599 (Polygon 1, 19.32x17)
            Figma와 동일하게 위를 향한 폴리곤을 180도 돌려 아래를 향하게 한다.
            right-[18px]는 꼬리 중심이 56px 로봇 버튼의 중심과 맞는 위치다.

            -bottom-[16px]는 꼬리 높이(17px)보다 1px 적다.
            Figma도 꼬리를 말풍선 안으로 1px 겹쳐 두는데, 딱 맞대면 경계에
            안티에일리어싱 때문에 실선 같은 틈이 생기기 때문이다.
          */}
          <span
            aria-hidden="true"
            className="absolute -bottom-[16px] right-[18px] block h-[17px] w-[19.32px] overflow-clip"
          >
            <img
              src={tooltipArrow}
              alt=""
              className="block size-full rotate-180"
            />
          </span>
        </div>
      )}

      {/* 로봇 버튼. Figma 111:2643 (56x56, Yellow/950 + Slate/100 보더 4px) */}
      <button
        type="button"
        onClick={onButtonClick}
        aria-label="선택한 가게 조사 시작"
        className="flex size-14 shrink-0 items-center justify-center overflow-clip rounded-full border-4 border-solid border-[#f1f5f9] bg-[#422006] shadow-[0px_3px_16px_4px_rgba(0,0,0,0.25)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6]"
      >
        <Icon
          icon="mingcute:robot-fill"
          className="size-8 shrink-0 text-white"
        />
      </button>
    </div>
  );
}

export default AgentSurveyTrigger;
