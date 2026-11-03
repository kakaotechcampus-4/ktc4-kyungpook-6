/** 사업자등록증 올리기 실패 문구 자리. 높이를 고정해 두어 문구가 떠도 버튼이 움직이지 않는다. */
function UploadErrorSlot({ message }: { message: string | null }) {
  return (
    <div className="flex min-h-12 items-center justify-center">
      {message && (
        <p role="alert" className="text-center text-[13px] leading-[1.4] text-[#e5484d]">
          {message}
        </p>
      )}
    </div>
  );
}

export default UploadErrorSlot;
