import manualIcon from "../../assets/check-in-manual-icon.svg";
import qrIcon from "../../assets/check-in-qr-icon.svg";

type CheckInMethodSheetProps = {
  orderNo: number;
  onSelectQr: () => void;
  onSelectManual: () => void;
  onClose: () => void;
};

type MethodOptionProps = {
  icon: string;
  label: string;
  description: string;
  onClick: () => void;
};

function MethodOption({ icon, label, description, onClick }: MethodOptionProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-3.5 rounded-[14px] border border-[#e5e8eb] bg-white p-4 text-left"
    >
      <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-[#fdf3dc]">
        <img src={icon} alt="" width={22} height={22} />
      </span>
      <span className="flex flex-col gap-[3px]">
        <span className="text-base leading-[normal] font-bold text-[#191f28]">{label}</span>
        <span className="text-[13px] leading-[normal] text-[#6b7684]">{description}</span>
      </span>
    </button>
  );
}

/** 주문의 "체크인"을 누르면 아래에서 올라오는 시트. QR 로 확인할지 직접 확인할지 고른다. */
function CheckInMethodSheet({ orderNo, onSelectQr, onSelectManual, onClose }: CheckInMethodSheetProps) {
  return (
    <div className="fixed inset-0 z-20 flex items-end justify-center">
      {/* 어두운 배경을 눌러도 닫힌다. */}
      <div className="absolute inset-0 bg-black/33" onClick={onClose} aria-hidden="true" />
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="check-in-sheet-title"
        className="relative flex w-full max-w-[420px] flex-col items-center gap-1 rounded-t-[20px] bg-white px-5 pt-3 pb-[max(20px,env(safe-area-inset-bottom))]"
      >
        <div className="h-1 w-9 rounded-xs bg-[#e5e8eb]" />
        <h2
          id="check-in-sheet-title"
          className="w-full pt-4 text-lg leading-[normal] font-bold text-[#191f28]"
        >
          방문 확인 방법을 선택해주세요
        </h2>
        <p className="w-full text-sm leading-[normal] text-[#6b7684]">주문 #{orderNo}</p>

        <div className="flex w-full flex-col gap-2 pt-4 pb-3">
          <MethodOption icon={qrIcon} label="QR로 확인" description="손님 QR을 스캔해요" onClick={onSelectQr} />
          <MethodOption
            icon={manualIcon}
            label="직접 확인"
            description="주문번호로 확인해요"
            onClick={onSelectManual}
          />
        </div>

        <button
          type="button"
          onClick={onClose}
          className="flex h-12 w-full items-center justify-center rounded-xl bg-[#f2f4f6] text-[15px] leading-[normal] font-semibold text-[#6b7684]"
        >
          닫기
        </button>
      </section>
    </div>
  );
}

export default CheckInMethodSheet;
