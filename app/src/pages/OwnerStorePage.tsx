import photoRemoveIcon from "../assets/photo-remove-icon.svg";
import plusSmallIcon from "../assets/plus-small-icon.svg";
import OwnerTabBar from "../components/owner/OwnerTabBar";
import BusinessHourRow from "../components/store/BusinessHourRow";
import { MenuRegisterRow, MenuRow } from "../components/store/MenuRow";
import { StoreInputField, StoreReadOnlyField } from "../components/store/StoreField";
import StoreMascot from "../components/store/StoreMascot";
import StoreSectionHeader from "../components/store/StoreSectionHeader";
import { useStoreManage } from "../hooks/storeManage";
import { MAX_MENU_NAME_LENGTH, MAX_PHONE_LENGTH } from "../utils/storeManage";

/** 구역 사이의 굵은 회색 줄. 화면 양옆 여백까지 꽉 채운다. */
function SectionDivider() {
  return <div className="-mx-5 mt-7 h-2 bg-[#f3f4f6]" />;
}

/** 점주 앱 "가게 관리" 탭: 기본 정보·영업시간·메뉴를 고치고 한 번에 저장한다. */
function OwnerStorePage() {
  const store = useStoreManage();

  return (
    <main className="min-h-screen bg-white pb-[calc(84px+env(safe-area-inset-bottom))]">
      {/* 아래로 내려도 "저장"을 누를 수 있게 제목 줄을 위에 붙여 둔다. */}
      <header className="sticky top-0 z-10 bg-white px-5 pt-[calc(12px+env(safe-area-inset-top))] pb-1">
        <div className="flex items-center justify-between">
          <h1 className="text-[22px] leading-[normal] font-bold text-[#1f2937]">가게 관리</h1>
          <button
            type="button"
            onClick={store.save}
            disabled={store.isLoading || store.loadErrorMessage !== null || store.isSaving}
            className="text-base leading-[normal] font-semibold text-[#b8891a] disabled:opacity-40"
          >
            {store.isSaving ? "저장 중" : "저장"}
          </button>
        </div>
        {store.notice && (
          <p
            role={store.notice.tone === "error" ? "alert" : "status"}
            className={`pt-2 text-sm leading-[normal] ${
              store.notice.tone === "error" ? "text-[#e5484d]" : "text-[#1f9d55]"
            }`}
          >
            {store.notice.text}
          </p>
        )}
      </header>

      {store.isLoading && <p className="px-5 pt-10 text-center text-sm text-[#6b7280]">불러오는 중…</p>}
      {store.loadErrorMessage && (
        <p className="px-5 pt-10 text-center text-sm text-[#6b7280]">{store.loadErrorMessage}</p>
      )}

      {!store.isLoading && !store.loadErrorMessage && (
        <div className="px-5 pb-7">
          {store.isMock && (
            <p className="mt-3 rounded-xl bg-[#fbf3dc] px-3.5 py-2.5 text-[13px] leading-[normal] text-[#8a6712]">
              서버에 가게 정보 기능이 아직 없어 예시 데이터를 보여 주고 있어요.
            </p>
          )}

          <section>
            <StoreSectionHeader title="기본 정보" className="pt-5" />
            <div className="flex flex-col gap-4">
              <StoreReadOnlyField label="상호명" value={store.name} />
              <StoreReadOnlyField label="주소" value={store.addressRoad} />
              <StoreInputField
                id="store-phone"
                label="가게 전화번호"
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                maxLength={MAX_PHONE_LENGTH}
                placeholder="가게 전화번호 입력"
                value={store.phone}
                onChange={(event) => store.setPhone(event.target.value)}
              />
              <div>
                <span className="block pb-2 text-sm leading-[normal] font-medium text-[#4b5563]">대표 사진</span>
                {/*
                  TODO(백엔드 대표 사진 API): StoreDetailResponse 에 사진 칸이 아직 없어 시안의 빈 자리만 그린다.
                  사진 올리기·지우기는 API 가 생기면 붙인다. 그 전까지 X 는 누를 수 없는 그림이다.
                */}
                <div className="relative flex size-[120px] items-center justify-center rounded-[14px] bg-[#fbf6e6]">
                  <StoreMascot size="large" />
                  <span aria-hidden className="absolute top-1 right-1 flex size-7 items-center justify-center">
                    <img src={photoRemoveIcon} alt="" width={18} height={18} />
                  </span>
                </div>
              </div>
            </div>
          </section>

          <SectionDivider />

          <section>
            <StoreSectionHeader
              title="영업시간"
              className="pt-6"
              action={
                <button
                  type="button"
                  onClick={store.addBusinessHour}
                  className="flex h-8 items-center gap-1 rounded-full bg-[#fbf3dc] pr-3 pl-[9px] text-[13px] leading-[normal] font-semibold text-[#b8891a]"
                >
                  <img src={plusSmallIcon} alt="" width={16} height={16} />
                  시간 추가
                </button>
              }
            />
            {store.businessHours.length === 0 ? (
              <p className="py-2 text-sm text-[#9ca3af]">영업시간을 추가해 주세요.</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {store.businessHours.map((hour) => (
                  <li key={hour.key}>
                    <BusinessHourRow
                      hour={hour}
                      onChange={(change) => store.changeBusinessHour(hour.key, change)}
                      onRemove={() => store.removeBusinessHour(hour.key)}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <SectionDivider />

          <section>
            <StoreSectionHeader title="메뉴" className="pt-6" />
            <MenuRegisterRow
              value={store.newMenuName}
              onChange={store.setNewMenuName}
              canRegister={store.canRegisterMenu}
              onRegister={store.registerMenu}
              maxLength={MAX_MENU_NAME_LENGTH}
            />
            <h3 className="pt-8 pb-3 text-[15px] leading-[normal] font-semibold text-[#6b7280]">
              등록한 메뉴 {store.menus.length}
            </h3>
            <ul className="flex flex-col gap-2">
              {store.menus.map((menu) => (
                <MenuRow
                  key={menu.key}
                  name={menu.name}
                  onSale={menu.onSale}
                  onToggleSale={() => store.toggleMenuOnSale(menu.key)}
                  onRemove={() => store.removeMenu(menu.key)}
                />
              ))}
            </ul>
          </section>
        </div>
      )}

      <OwnerTabBar />
    </main>
  );
}

export default OwnerStorePage;
