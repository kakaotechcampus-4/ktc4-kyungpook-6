import fieldPlusIcon from "../../assets/field-plus-icon.svg";
import fieldRemoveIcon from "../../assets/field-remove-icon.svg";
import TextField from "../ui/TextField";
import type { SignupBusinessPair } from "../../hooks/signupDraft";

type BusinessPairFieldsProps = {
  pairs: SignupBusinessPair[];
  onChange: (id: string, field: "type" | "item", value: string) => void;
  canAdd: boolean;
  onAdd: () => void;
  onRemove: (id: string) => void;
  labelClassName: string;
};

/* 업태 칸이 좁고 종목 칸이 넓다(피그마 131 : 196). */
const ROW_CLASS_NAME = "grid grid-cols-[2fr_3fr] gap-2";

/** 기본 정보의 업태·종목. 첫 줄 종목 칸의 + 로 줄을 더하고, 더한 줄은 x 로 지운다. */
function BusinessPairFields({ pairs, onChange, canAdd, onAdd, onRemove, labelClassName }: BusinessPairFieldsProps) {
  return (
    <div role="group" aria-label="업태 · 종목">
      <div className={`${ROW_CLASS_NAME} pb-2 text-sm leading-[normal] text-[#6b7280] ${labelClassName}`}>
        <span aria-hidden="true">업태</span>
        <span aria-hidden="true">종목</span>
      </div>

      {pairs.map((pair, index) => (
        <div key={pair.id} className={`${ROW_CLASS_NAME} pb-2 last:pb-0`}>
          <TextField
            id={`signup-business-type-${pair.id}`}
            label={`업태 ${index + 1}`}
            hideLabel
            value={pair.type}
            onChange={(event) => onChange(pair.id, "type", event.target.value)}
          />
          <TextField
            id={`signup-business-item-${pair.id}`}
            label={`종목 ${index + 1}`}
            hideLabel
            value={pair.item}
            onChange={(event) => onChange(pair.id, "item", event.target.value)}
            trailing={
              index === 0 ? (
                <button
                  type="button"
                  aria-label="업태 · 종목 줄 더하기"
                  onClick={onAdd}
                  disabled={!canAdd}
                  className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-white shadow-[0_0_0_1px_#e6e9ee] disabled:opacity-40"
                >
                  <img src={fieldPlusIcon} alt="" width={16} height={16} />
                </button>
              ) : (
                <button
                  type="button"
                  aria-label={`업태 · 종목 ${index + 1}번째 줄 지우기`}
                  onClick={() => onRemove(pair.id)}
                  className="flex size-7 shrink-0 items-center justify-center"
                >
                  <img src={fieldRemoveIcon} alt="" width={16} height={16} />
                </button>
              )
            }
          />
        </div>
      ))}
    </div>
  );
}

export default BusinessPairFields;
