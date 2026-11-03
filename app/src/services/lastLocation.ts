import { Preferences } from "@capacitor/preferences";

/*
  아동 홈 지도에서 마지막으로 받은 내 위치를 폰에 저장한다.
  다음에 홈에 들어오면 위치를 새로 받기 전까지 여기서 지도를 시작해 기준점(경북대)이 보이지 않게 한다.
  Preferences 는 Android/iOS 의 네이티브 저장소에 쓴다(웹에서는 localStorage).
*/
const LAST_LOCATION_KEY = "child.lastLocation";

export type SavedLocation = { lat: number; lng: number };

export const saveLastLocation = async (location: SavedLocation) => {
  await Preferences.set({ key: LAST_LOCATION_KEY, value: JSON.stringify(location) });
};

/** 저장한 위치. 없거나 값이 깨졌으면 null. */
export const loadLastLocation = async (): Promise<SavedLocation | null> => {
  const { value } = await Preferences.get({ key: LAST_LOCATION_KEY });
  if (!value) return null;
  try {
    const parsed: unknown = JSON.parse(value);
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      "lat" in parsed &&
      "lng" in parsed &&
      Number.isFinite(parsed.lat) &&
      Number.isFinite(parsed.lng)
    ) {
      return { lat: parsed.lat as number, lng: parsed.lng as number };
    }
  } catch {
    // 깨진 값은 없는 것으로 본다.
  }
  return null;
};
