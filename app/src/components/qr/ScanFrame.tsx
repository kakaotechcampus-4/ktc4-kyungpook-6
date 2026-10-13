/** 네 모서리 꺾쇠 하나의 위치·테두리. 피그마 div.frame 의 i 4개. */
const CORNERS = [
  "top-0 left-0 rounded-tl-[14px] border-t-4 border-l-4",
  "top-0 right-0 rounded-tr-[14px] border-t-4 border-r-4",
  "bottom-0 left-0 rounded-bl-[14px] border-b-4 border-l-4",
  "bottom-0 right-0 rounded-br-[14px] border-b-4 border-r-4",
];

/** QR 을 맞출 네모. 노란 꺾쇠 네 개와 가운데 스캔 선. */
function ScanFrame() {
  return (
    <div className="relative size-60" aria-hidden>
      {CORNERS.map((corner) => (
        <i key={corner} className={`absolute size-11 border-solid border-[#e3b23c] ${corner}`} />
      ))}
      <div className="absolute top-[calc(50%+1px)] right-4 left-4 h-0.5 -translate-y-1/2 bg-[#e3b23c] shadow-[0_0_12px_0_#e3b23c]" />
    </div>
  );
}

export default ScanFrame;
