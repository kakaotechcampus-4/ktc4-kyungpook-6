import ScanHeader from "../components/qr/ScanHeader";
import ScanFrame from "../components/qr/ScanFrame";
import { useQrScanner } from "../hooks/qrScanner";
import { useQrCheckIn } from "../hooks/qrCheckIn";

/** QR 확인: 아동이 보여 주는 QR 을 찍어 체크인한다. */
function OwnerQrScanPage() {
  const { handleScan, errorMessage } = useQrCheckIn();
  const { containerRef, cameraError } = useQrScanner(handleScan);

  // 카메라가 안 켜지면 체크인 실패보다 그쪽이 먼저다.
  const alertMessage = cameraError ?? errorMessage;

  return (
    <main className="relative flex min-h-screen flex-col overflow-hidden bg-[#111827] pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      {/* 카메라 화면 자리. 켜지기 전·켜지지 못했을 때는 배경색만 보인다. */}
      <div ref={containerRef} className="absolute inset-0" />
      {/* 밝은 카메라 화면 위에서도 흰 글자가 읽히게 배경색으로 한 겹 어둡게 덮는다(피그마의 어두운 배경). */}
      <div className="absolute inset-0 bg-[#111827]/50" />

      <div className="relative flex flex-1 flex-col">
        <ScanHeader title="아동 확인" />

        <section className="flex flex-1 flex-col items-center justify-center px-5 text-center">
          <ScanFrame />
          <p className="pt-7 text-[17px] leading-[normal] font-semibold text-white">
            아동의 QR을 네모 안에 맞춰주세요
          </p>
          {/* 안내 문구 자리에 실패 문구를 대신 띄운다. 높이가 같아 네모가 움직이지 않는다. */}
          {alertMessage ? (
            <p role="alert" className="pt-1.5 text-sm leading-[normal] text-[#e5484d]">
              {alertMessage}
            </p>
          ) : (
            <p className="pt-1.5 text-sm leading-[normal] text-white opacity-60">
              주문 정보와 자동으로 맞춰봐요
            </p>
          )}
        </section>
      </div>
    </main>
  );
}

export default OwnerQrScanPage;
