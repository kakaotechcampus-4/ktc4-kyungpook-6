import type { FormEvent } from "react";
import { Navigate } from "react-router-dom";
import LogoHeader from "../components/ui/LogoHeader";
import PrimaryButton from "../components/ui/PrimaryButton";
import StoreStepProgress from "../components/signup/StoreStepProgress";
import { MenuCard, NewMenuCard } from "../components/signup/MenuCards";
import { useSignupDraft } from "../hooks/signupDraft";
import { useSignupMenus } from "../hooks/signupMenus";

const SECTION_TITLE_CLASS_NAME = "pb-3 text-sm leading-[normal] font-semibold text-[#6b7280]";

/** 가게 등록 3: 메뉴. "다음"을 누르면 가입 신청을 보낸다. */
function OwnerSignupMenusPage() {
  const { account, info, store, businessHours } = useSignupDraft();
  const {
    newMenuName,
    setNewMenuName,
    newMenuPhoto,
    setNewMenuPhoto,
    canRegister,
    register,
    menus,
    removeMenu,
    canProceed,
    goNext,
    errorMessage,
  } = useSignupMenus();

  // 앞 단계를 거치지 않고 들어오면(새로고침 등) 입력값이 없으니 1단계로 돌려보낸다.
  if (!account || !info || !store || !businessHours) return <Navigate to="/owner/signup" replace />;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    goNext();
  };

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <LogoHeader title="가게 등록" description="제공할 메뉴를 알려주세요." />

      <form onSubmit={handleSubmit} className="flex flex-1 flex-col">
        <h2 className={SECTION_TITLE_CLASS_NAME}>새 메뉴</h2>
        <NewMenuCard
          name={newMenuName}
          onNameChange={setNewMenuName}
          photo={newMenuPhoto}
          onPhotoChange={setNewMenuPhoto}
          canRegister={canRegister}
          onRegister={register}
        />

        {menus.length > 0 && (
          <>
            <h2 className={`${SECTION_TITLE_CLASS_NAME} pt-8`}>등록한 메뉴 {menus.length}</h2>
            <ul className="flex flex-col gap-2">
              {menus.map((menu) => (
                <MenuCard
                  key={menu.id}
                  name={menu.name}
                  photo={menu.photo}
                  onRemove={() => removeMenu(menu.id)}
                />
              ))}
            </ul>
          </>
        )}

        {/* 가입 신청 실패 문구 자리. 높이를 고정해 문구가 떠도 버튼이 움직이지 않는다. */}
        <div className="mt-auto flex min-h-12 items-center justify-center pt-6">
          {errorMessage && (
            <p role="alert" className="text-center text-[13px] leading-[1.4] text-[#e5484d]">
              {errorMessage}
            </p>
          )}
        </div>

        <StoreStepProgress current={3} />
        <PrimaryButton type="submit" disabled={!canProceed}>
          다음
        </PrimaryButton>
      </form>
    </main>
  );
}

export default OwnerSignupMenusPage;
