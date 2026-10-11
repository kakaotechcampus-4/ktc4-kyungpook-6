import logo from "../../assets/good-radar-logo.png";

/** 스플래시 가운데의 로고와 앱 이름. */
function SplashBrand() {
  return (
    <div className="flex flex-col items-center">
      <img src={logo} alt="" className="size-28" />
      <h1 className="pt-5 text-[28px] leading-[normal] font-extrabold text-white">
        선한레이더
      </h1>
      <p className="pt-0.5 text-base leading-[normal] font-semibold text-white opacity-85">
        Good Radar
      </p>
    </div>
  );
}

export default SplashBrand;
