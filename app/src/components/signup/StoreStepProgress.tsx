type StoreStepProgressProps = {
  /** 지금 단계(1부터). 이 단계까지 노란색으로 채운다. */
  current: 1 | 2 | 3;
};

const STEP_COUNT = 3;

/** 가게 등록 세 단계(가게 정보 → 영업시간 → 메뉴)의 진행 막대. "다음" 버튼 바로 위에 둔다. */
function StoreStepProgress({ current }: StoreStepProgressProps) {
  return (
    <div
      role="progressbar"
      aria-label="가게 등록 단계"
      aria-valuemin={1}
      aria-valuemax={STEP_COUNT}
      aria-valuenow={current}
      className="flex gap-[13px] pb-5"
    >
      {Array.from({ length: STEP_COUNT }, (_, index) => (
        <span
          key={index}
          className={[
            "h-1.5 flex-1 rounded-[10px]",
            index < current ? "bg-[#e3b23c]" : "bg-[#dcdcdc]",
          ].join(" ")}
        />
      ))}
    </div>
  );
}

export default StoreStepProgress;
