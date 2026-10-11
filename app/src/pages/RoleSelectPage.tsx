import logo from "../assets/good-radar-logo.png";
import storeIcon from "../assets/store-icon.svg";
import cardIcon from "../assets/card-icon.svg";
import RoleCard from "../components/role/RoleCard";
import PrimaryButton from "../components/ui/PrimaryButton";
import { useRoleSelect } from "../hooks/roleSelect";

function RoleSelectPage() {
  const { selectedRole, selectRole, goNext } = useRoleSelect();

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <header className="flex flex-col gap-4 pt-10 pb-8">
        <img src={logo} alt="" className="size-12" />
        <h1 className="pt-1 text-[22px] leading-[1.4] font-bold text-[#1f2937]">
          어떻게 이용하시나요?
        </h1>
        <p className="text-sm leading-[normal] text-[#6b7280]">
          처음 한 번만 선택하면 다음부터 바로 시작해요.
        </p>
      </header>

      <div className="flex flex-col gap-3.5">
        <RoleCard
          icon={storeIcon}
          title="가게 사장님이에요"
          description="발급받은 아이디로 로그인해요"
          selected={selectedRole === "OWNER"}
          onSelect={() => selectRole("OWNER")}
        />
        <RoleCard
          icon={cardIcon}
          title="식사하러 왔어요"
          description="아동급식카드로 인증해요"
          selected={selectedRole === "CHILD"}
          onSelect={() => selectRole("CHILD")}
        />
      </div>

      <PrimaryButton className="mt-auto" disabled={!selectedRole} onClick={goNext}>
        다음
      </PrimaryButton>
    </main>
  );
}

export default RoleSelectPage;
