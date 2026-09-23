import { Icon } from '@iconify/react';
import { useEffect, useId, useRef } from 'react';
import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import type { StoreStatus } from '../../services/store';
import type { StoreEditFormValues } from '../../hooks/storeEdit';

/**
 * 운영 상태 선택지. 값은 백엔드 StoreStatus, 라벨은 화면 표기다.
 * 순서는 enum 선언 순서를 따른다. Figma 103:124
 */
const STATUS_OPTIONS: { value: StoreStatus; label: string }[] = [
  { value: 'OPEN', label: '영업중' },
  { value: 'SUSPENDED', label: '휴업' },
  { value: 'CLOSED', label: '폐업' },
  { value: 'UNKNOWN', label: '미확인' },
];

/** 섹션 제목("가게 기본 정보", "운영 정보", "자체 확인 정보"). Figma 103:62 */
const SECTION_LABEL =
  'shrink-0 whitespace-nowrap font-sans text-sm font-medium leading-5 text-[#475569]';

/** 항목 라벨("상호명", "주소", …). Figma 103:67 */
const FIELD_LABEL =
  'shrink-0 whitespace-nowrap font-sans text-sm font-medium leading-5 text-[#64748b]';

/** 입력 칸. Slate/100 배경 + radius 6, w 288. Figma 103:105 */
const FIELD_INPUT =
  'w-72 rounded-md bg-[#f1f5f9] px-2.5 py-[5px] font-sans text-sm font-normal leading-5 text-black focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6]';

type SectionProps = {
  /** 섹션 제목. 오른쪽으로 가로줄이 이어진다. */
  title: string;
  children: ReactNode;
};

/** 제목 + 가로줄 한 줄과 그 아래 항목들. Figma 103:60 / 103:115 / 103:138 */
function Section({ title, children }: SectionProps) {
  return (
    <section className="flex w-full flex-col items-start gap-1.5">
      <div className="flex w-full items-center gap-2.5">
        <h3 className={SECTION_LABEL}>{title}</h3>
        {/* 제목 오른쪽을 채우는 1px 구분선. Figma 103:63 */}
        <div className="h-px min-w-px flex-1 bg-[#64748b]" />
      </div>
      {/*
        Figma 103:64 에는 overflow-clip 이 걸려 있지만 그대로 옮기지 않는다.
        입력 칸이 이 영역의 오른쪽 끝에 딱 붙어 있어, 키보드로 이동했을 때 나오는
        파란 테두리(2px 바깥쪽)가 잘려 보인다.
      */}
      <div className="flex w-full flex-col items-start gap-2">{children}</div>
    </section>
  );
}

type FieldProps = {
  /** 왼쪽 라벨. */
  label: string;
  /** 라벨이 가리키는 입력 칸의 id. */
  htmlFor?: string;
  /** 오른쪽 입력 칸 또는 표시 값. */
  children: ReactNode;
};

/** 라벨 왼쪽 / 값 오른쪽 한 줄. Figma 103:66 */
function Field({ label, htmlFor, children }: FieldProps) {
  return (
    <div className="flex w-full items-center justify-between gap-2.5">
      <label htmlFor={htmlFor} className={FIELD_LABEL}>
        {label}
      </label>
      {children}
    </div>
  );
}

type StoreEditModalProps = {
  /** 모달 열림 여부. 내부에서 showModal/close를 호출해 이 값과 맞춘다. */
  open: boolean;
  /** 입력 칸의 현재 값. */
  values: StoreEditFormValues;
  /** 입력 칸 하나가 바뀔 때. */
  onChange: <K extends keyof StoreEditFormValues>(
    field: K,
    value: StoreEditFormValues[K]
  ) => void;
  /** "자체 확인일"에 표시할 문구. 없으면 "-"로 그린다. */
  lastCheckedAtLabel?: string;
  /** "저장하기" 클릭. */
  onSave: () => void;
  /** "자체 확인 완료" 클릭. */
  onConfirm: () => void;
  /** ESC로 닫을 때. */
  onClose: () => void;
  /** 저장 요청 중 여부. */
  isSaving?: boolean;
  /** 확인 기록 요청 중 여부. */
  isConfirming?: boolean;
  /** 검증·요청 실패 안내. */
  errorMessage?: string;
} & Omit<
  ComponentPropsWithoutRef<'dialog'>,
  'children' | 'open' | 'onCancel' | 'onClose' | 'onChange'
>;

/**
 * 가게 정보 수정 모달. Figma Modal(102:4996) / 102:4997
 *
 * 담당자가 조사 결과를 믿지 못하거나 결과에 없는 값을 고칠 때 쓰는 화면이다.
 * 값을 들고 있지 않고 받은 값을 그리기만 한다 — 어느 가게를 고치는지,
 * 무엇이 바뀌었는지는 useStoreEditModal이 안다.
 *
 * 네이티브 <dialog>의 showModal을 쓴다. 포커스 트랩, ESC 닫기, 배경 딤(::backdrop),
 * 뒤쪽 콘텐츠 비활성화를 브라우저가 처리해주기 때문이다.
 * 배경 클릭으로는 닫지 않는다. <dialog>에서는 카드의 padding 영역 클릭도
 * 배경 클릭과 구분되지 않아 오작동하기 쉽다.
 *
 * "자체 확인 완료"가 저장과 따로 있는 이유는 API가 나뉘어 있는 것과 같다.
 * 확인일은 담당자가 값을 정하는 게 아니라 "지금 확인했다"는 기록이라
 * 저장하기와 묶으면 값을 고치지 않았는데도 확인일이 갱신된다.
 */
function StoreEditModal({
  open,
  values,
  onChange,
  lastCheckedAtLabel,
  onSave,
  onConfirm,
  onClose,
  isSaving = false,
  isConfirming = false,
  errorMessage,
  className,
  ...props
}: StoreEditModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const nameId = useId();
  const addressId = useId();
  const phoneId = useId();
  const statusId = useId();

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      /*
        ESC로 닫을 때 네이티브 close를 막고 onClose만 호출한다.
        열림 상태는 open prop이 유일한 기준이어야 하는데, 네이티브가 먼저 닫아버리면
        부모의 상태는 열린 채로 남아 다음 열기가 동작하지 않는다.
      */
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      /*
        m-auto가 가운데 정렬을 만든다.
        브라우저 기본 스타일은 dialog:modal에 `inset: 0` + `margin: auto`를 걸어 중앙에 놓는데,
        Tailwind preflight의 `*, ::backdrop { margin: 0 }`이 그 margin을 덮어써서
        m-auto를 다시 주지 않으면 화면 왼쪽 위에 붙는다.
      */
      className={[
        'm-auto flex-col items-start overflow-clip rounded-xl border-0 bg-white p-5 shadow-[0px_4px_16px_4px_rgba(0,0,0,0.25)] backdrop:bg-[rgba(0,0,0,0.33)] open:flex',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      {/*
        form으로 감싸 Enter로도 저장되게 한다.
        카드 안쪽 간격(12px)은 이 form이 들고 있다. Figma 102:4997
      */}
      <form
        className="flex w-[369px] max-w-full flex-col items-start gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        {/*
          제목 + 닫기. Figma 102:4998 에는 제목만 있고 X 버튼이 없지만,
          ESC 말고 마우스로 닫을 방법이 없으면 곤란해서 넣었다.
          버튼을 20px로 맞춰 제목 줄 높이(20px)는 Figma 그대로 둔다.
        */}
        <div className="flex w-full items-center justify-between gap-2.5">
          <h2
            id={titleId}
            className="font-sans text-sm font-medium leading-5 text-[#475569]"
          >
            가게 정보 수정
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="flex size-5 shrink-0 items-center justify-center rounded text-[#64748b] hover:text-[#1e293b] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#64748b]"
          >
            <Icon icon="lucide:x" className="size-4 shrink-0" />
          </button>
        </div>

        {/* 가게 기본 정보. Figma 103:60 */}
        <Section title="가게 기본 정보">
          <Field label="상호명" htmlFor={nameId}>
            <input
              id={nameId}
              type="text"
              value={values.name}
              onChange={(event) => onChange('name', event.target.value)}
              maxLength={200}
              className={FIELD_INPUT}
            />
          </Field>
          <Field label="주소" htmlFor={addressId}>
            <input
              id={addressId}
              type="text"
              value={values.addressRoad}
              onChange={(event) => onChange('addressRoad', event.target.value)}
              maxLength={500}
              className={FIELD_INPUT}
            />
          </Field>
          <Field label="전화번호" htmlFor={phoneId}>
            <input
              id={phoneId}
              type="tel"
              value={values.phone}
              onChange={(event) => onChange('phone', event.target.value)}
              maxLength={20}
              placeholder="010-XXXX-XXXX"
              className={FIELD_INPUT}
            />
          </Field>
        </Section>

        {/* 운영 정보. Figma 103:115 */}
        <Section title="운영 정보">
          <Field label="운영 상태" htmlFor={statusId}>
            {/*
              화살표는 Figma의 gridicons:dropdown 노드(103:136)다.
              브라우저 기본 화살표를 지우고(appearance-none) 같은 자리에 겹쳐 그린다.
            */}
            <div className="relative w-72">
              <select
                id={statusId}
                value={values.status}
                onChange={(event) =>
                  onChange('status', event.target.value as StoreStatus)
                }
                className={`${FIELD_INPUT} w-full appearance-none pr-9`}
              >
                {STATUS_OPTIONS.map(({ value, label }) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
              <Icon
                icon="gridicons:dropdown"
                aria-hidden="true"
                className="pointer-events-none absolute right-2.5 top-1/2 size-5 -translate-y-1/2 text-black"
              />
            </div>
          </Field>
        </Section>

        {/* 자체 확인 정보. Figma 103:138 */}
        <Section title="자체 확인 정보">
          <Field label="자체 확인일">
            <div className="flex w-72 items-center justify-center gap-2.5 overflow-clip">
              <p className="min-w-px flex-1 text-right font-sans text-sm font-normal leading-5 text-black">
                {lastCheckedAtLabel ?? '-'}
              </p>
              <button
                type="button"
                onClick={onConfirm}
                disabled={isConfirming}
                className="flex shrink-0 items-center justify-center overflow-clip rounded-[2px] border border-solid border-[#e2e8f0] bg-white px-2 py-1.5 text-right font-sans text-xs font-medium leading-4 text-[#1e293b] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#64748b] disabled:opacity-60"
              >
                자체 확인 완료
              </button>
            </div>
          </Field>
        </Section>

        {/*
          실패 안내. 대응하는 Figma 노드는 없지만 저장·확인이 서버 요청이라
          실패를 말없이 넘기면 담당자가 저장된 줄 안다.
          분석 결과 화면의 조회 실패 안내와 같은 이유로 둔다.
        */}
        {errorMessage && (
          <p
            role="alert"
            className="w-full font-sans text-sm font-normal leading-5 text-[#ef4444]"
          >
            {errorMessage}
          </p>
        )}

        {/* 저장하기. Figma 102:5009 */}
        <div className="flex w-full flex-col items-start">
          <button
            type="submit"
            /* 같은 요청이 두 번 나가지 않도록 요청 중에는 막는다. */
            disabled={isSaving}
            className="flex h-10 w-full shrink-0 items-center justify-center gap-1 overflow-clip rounded-lg bg-[#3b82f6] p-2.5 font-sans text-sm font-semibold leading-5 text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#3b82f6] disabled:opacity-60"
          >
            저장하기
          </button>
        </div>
      </form>
    </dialog>
  );
}

export default StoreEditModal;
