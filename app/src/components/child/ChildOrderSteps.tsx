import type { ChildOrderStatus } from "../../services/childQr";

const STEPS: { status: ChildOrderStatus; label: string }[] = [
  { status: "RECEIVED", label: "주문 접수" },
  { status: "COOKING", label: "조리 시작" },
];

/** 내 QR 의 주문 상태 단계. 지금 단계까지 동그라미를 노랗게 칠하고, 글자는 지금 단계만 굵게 한다. */
function ChildOrderSteps({ status }: { status: ChildOrderStatus }) {
  const currentIndex = STEPS.findIndex((step) => step.status === status);

  return (
    <ol className="relative flex px-5">
      {/* 동그라미 사이를 잇는 선. 첫 칸과 마지막 칸의 가운데에서 끝난다. */}
      <span aria-hidden className="absolute inset-x-[50px] top-3 h-0.5 bg-[#e6e9ee]" />
      {STEPS.map(({ status: stepStatus, label }, index) => {
        const done = index <= currentIndex;
        const current = index === currentIndex;
        return (
          <li
            key={stepStatus}
            aria-current={current ? "step" : undefined}
            className="relative flex flex-1 flex-col items-center gap-2"
          >
            <span
              className={`flex size-[26px] items-center justify-center rounded-full border text-[11px] leading-[normal] ${
                done ? "border-[#e2b33c] bg-[#e2b33c] text-white" : "border-[#e6e9ee] bg-white text-[#a0a7b2]"
              }`}
            >
              {index + 1}
            </span>
            <span
              className={`text-xs leading-[normal] ${current ? "font-bold text-[#1f2937]" : "text-[#a0a7b2]"}`}
            >
              {label}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

export default ChildOrderSteps;
