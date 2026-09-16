import type { ComponentPropsWithoutRef, ReactNode } from 'react';
import Skeleton from '../ui/Skeleton';

type AnalysisResultSectionProps = {
  /** 섹션 제목. 괄호 안 건수는 count로 따로 받는다. Figma 112:5453 */
  title: string;
  /** 제목 옆 괄호에 들어갈 건수. */
  count: number;
  /** 건수 조회 중 여부. 제목은 정적 텍스트라 숫자 자리만 Skeleton으로 둔다. */
  isLoading?: boolean;
  /** 카드 목록. 2열 그리드에 그대로 깔린다. */
  children: ReactNode;
} & Omit<ComponentPropsWithoutRef<'section'>, 'children' | 'title'>;

/**
 * 분석 결과 화면의 분류별 섹션. 제목 한 줄과 카드 2열 그리드로 이루어진다.
 * Figma 112:5452 안의 funnelT + Frame 193/194/195
 *
 * 한 행의 카드들은 높이를 맞춘다(grid 기본 stretch).
 * 근거 수가 달라 카드 내용 길이가 제각각인데, 버튼 위치까지 들쭉날쭉하면
 * 여러 건을 연달아 처리할 때 마우스를 매번 다시 찾아야 한다. Figma 112:5455도
 * 내용이 짧지만 옆 카드와 같은 368px로 잡혀 있다.
 */
function AnalysisResultSection({
  title,
  count,
  isLoading = false,
  children,
  className,
  ...props
}: AnalysisResultSectionProps) {
  return (
    <section
      className={['@container flex w-full flex-col items-start gap-3', className]
        .filter(Boolean)
        .join(' ')}
      {...props}
    >
      <h2 className="font-sans text-base font-medium leading-6 text-[#475569]">
        {title} (
        {isLoading ? (
          <Skeleton className="h-4 w-3 align-[-0.1em]" />
        ) : (
          count
        )}
        )
      </h2>

      {/*
        Figma는 554px 카드 2개 + 16px 간격(1124px)인 고정 2열이다.

        카드 최소 폭 360px: 가장 긴 근거 문장이 333px이고 불릿 들여쓰기 21px,
        카드 좌우 패딩 24px을 더하면 378px에서 한 줄에 들어간다.
        360px이면 그 한 문장만 두 줄로 접히고 나머지는 멀쩡해서, 여기까지는 2열이 낫다.
        그래서 두 장 + gap 16px = 736px부터 2열로 둔다.

        뷰포트가 아니라 @container로 재는 이유:
        기준이 되는 건 창 폭이 아니라 사이드바(128px)와 패딩을 뺀 이 섹션의 실제 폭이다.
        뷰포트로 재면 사이드바 폭을 바꾸는 순간 브레이크포인트를 같이 고쳐야 한다.
      */}
      <div className="grid w-full grid-cols-1 items-stretch gap-4 @min-[736px]:grid-cols-2">
        {children}
      </div>
    </section>
  );
}

export default AnalysisResultSection;
