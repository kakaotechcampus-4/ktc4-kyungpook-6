import faceLarge from "../../assets/mascot-face-large.svg";
import sparkleLarge from "../../assets/mascot-sparkle-large.svg";
import face from "../../assets/mascot-face.svg";
import sparkle from "../../assets/mascot-sparkle.svg";

type StoreMascotProps = {
  /** large: 대표 사진 자리(64px), small: 메뉴 썸네일(36px). */
  size: "large" | "small";
};

const ASSETS = {
  large: { box: "size-16", face: faceLarge, sparkle: sparkleLarge },
  small: { box: "size-9", face, sparkle },
};

/**
 * 사진이 없을 때 자리를 채우는 연한 마스코트. 피그마 시안대로 두 그림을 겹치고 위아래로 뒤집는다.
 * 안쪽 상자는 각 SVG 원래 크기와 같아서 그림 크기를 따로 정하지 않는다.
 */
function StoreMascot({ size }: StoreMascotProps) {
  const asset = ASSETS[size];
  return (
    <div className={`relative shrink-0 opacity-55 ${asset.box}`}>
      <div className="absolute inset-[13.55%_0.9%_8.41%_2.34%] -scale-y-100">
        <img src={asset.face} alt="" className="absolute inset-0 block" />
        <img src={asset.sparkle} alt="" className="absolute inset-0 block" />
      </div>
    </div>
  );
}

export default StoreMascot;
