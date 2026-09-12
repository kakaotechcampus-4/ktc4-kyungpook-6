import type { ComponentPropsWithoutRef } from 'react';

type SkeletonProps = Omit<ComponentPropsWithoutRef<'span'>, 'children'>;

/**
 * API 응답을 기다리는 텍스트 노드 자리에 표시하는 로딩 플레이스홀더.
 * 크기는 대체하려는 텍스트 노드의 치수를 className으로 넘겨 맞춘다.
 */
function Skeleton({ className, ...props }: SkeletonProps) {
  return (
    <span
      aria-hidden="true"
      className={['inline-block animate-pulse rounded bg-[#e2e8f0]', className]
        .filter(Boolean)
        .join(' ')}
      {...props}
    />
  );
}

export default Skeleton;
