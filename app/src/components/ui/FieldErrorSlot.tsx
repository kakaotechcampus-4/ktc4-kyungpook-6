type FieldErrorSlotProps = {
  id: string;
  /** 보여 줄 문구. 없으면 빈 자리만 차지한다. */
  message: string | null;
};

/**
 * 입력 칸 아래의 에러 문구 자리.
 *
 * 높이를 고정해 두어 문구가 떠도 아래 칸이 밀리지 않는다.
 * 입력 칸의 aria-describedby 에 id 를 넘기면 화면 낭독기가 문구를 함께 읽는다.
 */
function FieldErrorSlot({ id, message }: FieldErrorSlotProps) {
  return (
    <div className="h-7 px-4 pt-1">
      {message && (
        <p id={id} className="text-xs leading-[normal] text-[#e5484d]">
          {message}
        </p>
      )}
    </div>
  );
}

export default FieldErrorSlot;
