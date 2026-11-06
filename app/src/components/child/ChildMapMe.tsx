import headingArrow from "../../assets/child-heading-arrow.svg";
import logo from "../../assets/good-radar-logo.png";

/* 바깥 원의 노란 빛. 피그마 radialGradient 값 그대로(투명도 0.5). 깜빡이지 않고 늘 같다. */
const GLOW_BACKGROUND =
  "radial-gradient(circle closest-side, rgb(227 178 60 / 0.4) 0%, rgb(227 178 60 / 0.05) 100%)";

const CENTER = "absolute top-1/2 left-1/2 -translate-1/2 rounded-full";
/* 점선 원. 움직임 줄이기를 켜면 깜빡이지 않고 그대로 보인다. */
const RING_CLASS = `${CENTER} border border-dashed border-[#e3b23c] motion-safe:animate-ring-blink`;

type ChildMapMeProps = {
  /** 폰이 향한 방향(북쪽 0°, 시계 방향). null 이면 방향 화살표를 그리지 않는다. */
  heading: number | null;
};

/**
 * 지도 위 내 위치. 마스코트 둘레에 노란 빛과 점선 원 세 겹을 그린다. 가운데가 내 위치다.
 * 점선 원은 안쪽 → 가운데 → 바깥 순서로 깜빡인다. 시안의 원(220·160·101px)을 두 번째 원 크기에 맞춰 줄였다.
 * 방향을 알면 마스코트 바로 바깥에 남색(마스코트 테두리색) 화살표로 폰이 향한 쪽을 가리킨다. 지도는 늘 북쪽이 위다.
 */
function ChildMapMe({ heading }: ChildMapMeProps) {
  return (
    <div aria-label="내 위치" className="pointer-events-none relative size-40">
      <span className={`${CENTER} size-40`} style={{ background: GLOW_BACKGROUND }} />
      <span className={`${RING_CLASS} size-[73px]`} />
      <span className={`${RING_CLASS} size-[116px] [animation-delay:0.3s]`} />
      <span className={`${RING_CLASS} size-40 [animation-delay:0.6s]`} />
      {heading !== null && (
        <span
          aria-hidden="true"
          className="absolute inset-0 transition-transform duration-200 ease-out"
          style={{ transform: `rotate(${heading}deg)` }}
        >
          <img
            src={headingArrow}
            alt=""
            width={14}
            height={10}
            className="absolute top-[calc(50%-31px)] left-1/2 -translate-x-1/2"
          />
        </span>
      )}
      <img src={logo} alt="" className="absolute top-1/2 left-1/2 size-9 -translate-1/2" />
    </div>
  );
}

export default ChildMapMe;
