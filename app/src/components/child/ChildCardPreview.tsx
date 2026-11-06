import cardIcon from "../../assets/child-card-white-icon.svg";
import { CHILD_CARD_NUMBER_LENGTH } from "../../hooks/childCardLogin";

type ChildCardPreviewProps = {
  /** 입력 중인 카드 번호(숫자만). */
  cardNumber: string;
};

/*
  "1234 **** **** 5678" 처럼 앞 4자리·뒤 4자리만 보이고 가운데 8자리는 가린다.
  아직 입력하지 않은 자리도 * 로 채워, 입력 전에는 "**** **** **** ****" 로 보인다.
*/
const toMaskedCardNumber = (cardNumber: string) =>
  Array.from({ length: CHILD_CARD_NUMBER_LENGTH }, (_, index) => {
    const isVisible = index < 4 || index >= 12;
    return isVisible ? (cardNumber[index] ?? "*") : "*";
  })
    .join("")
    .replace(/(.{4})(?=.)/g, "$1 ");

/** 카드 번호 입력 칸 위의 파란 카드 그림. 입력한 번호가 바로 비친다. */
function ChildCardPreview({ cardNumber }: ChildCardPreviewProps) {
  return (
    <div className="flex h-40 w-full flex-col justify-between rounded-2xl bg-[linear-gradient(135deg,#b9ccf6_0%,#3f66cc_100%)] p-5">
      <img src={cardIcon} alt="" width={26} height={26} />
      <p aria-hidden className="text-lg leading-[normal] font-medium tracking-[3px] text-white">
        {toMaskedCardNumber(cardNumber)}
      </p>
    </div>
  );
}

export default ChildCardPreview;
