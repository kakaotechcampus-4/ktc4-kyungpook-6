import checkIcon from "../../assets/check-icon.svg";

type StepState = "done" | "current" | "todo";

type ApprovalProgressTrackProps = {
  /** 승인됐으면 세 단계 모두 끝난 것으로 그린다. */
  approved: boolean;
};

const STEPS = ["가입 요청", "관리자 확인 중", "승인 완료"];

const stepStates = (approved: boolean): StepState[] =>
  approved ? ["done", "done", "done"] : ["done", "current", "todo"];

function StepMarker({ state }: { state: StepState }) {
  if (state === "done") {
    return (
      <span className="flex size-[22px] shrink-0 items-center justify-center rounded-full border-2 border-[#e3b23c] bg-[#e3b23c]">
        <img src={checkIcon} alt="" width={12} height={12} />
      </span>
    );
  }
  if (state === "current") {
    // 26px 노란 원 안쪽을 흰색 6px 로 채워 가운데 점만 남긴다. 다른 원(22px)과 가운데를 맞춘다.
    return (
      <span className="relative size-[22px] shrink-0">
        <span className="absolute -inset-0.5 rounded-full border-2 border-[#e3b23c] bg-[#e3b23c] shadow-[inset_0px_0px_0px_6px_white]" />
      </span>
    );
  }
  return <span className="size-[22px] shrink-0 rounded-full border-2 border-[#e5e7eb]" />;
}

const LABEL_CLASS_NAME: Record<StepState, string> = {
  done: "text-[#1f2937]",
  current: "font-semibold text-[#b8891a]",
  todo: "text-[#9ca3af]",
};

/** 가입 완료 화면의 "가입 요청 → 관리자 확인 중 → 승인 완료" 진행 표시. */
function ApprovalProgressTrack({ approved }: ApprovalProgressTrackProps) {
  const states = stepStates(approved);

  return (
    <ol className="w-full rounded-2xl bg-[#f8f9fb] px-4 py-[18px]">
      {STEPS.map((label, index) => {
        const state = states[index];
        const next = states[index + 1];
        return (
          <li key={label} className="relative flex items-center gap-3 py-2">
            <StepMarker state={state} />
            <span className={`text-[15px] leading-[normal] ${LABEL_CLASS_NAME[state]}`}>{label}</span>
            {/* 다음 단계로 잇는 선. 다음 단계에 도달했으면 노란색. */}
            {next && (
              <span
                aria-hidden="true"
                className={[
                  "absolute top-[30px] left-[10px] h-4 w-0.5 rounded-[1px]",
                  next === "todo" ? "bg-[#e5e7eb]" : "bg-[#e3b23c]",
                ].join(" ")}
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}

export default ApprovalProgressTrack;
