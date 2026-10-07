import type { ComponentPropsWithoutRef } from "react";
import InputField from "../components/ui/InputField";
import { useLoginPage } from "../hooks/login";
import goodRadarLogo from "../assets/good-radar-logo.png";

type LoginPageProps = Omit<ComponentPropsWithoutRef<"div">, "children">;

/**
 * 관리자 로그인 페이지. Figma Login(515:27)
 *
 * 백엔드 로그인은 이메일로 받는다. 화면 라벨은 디자인대로 "아이디"를 두고 값은 email 로 보낸다.
 */
function LoginPage({ className, ...props }: LoginPageProps) {
  const {
    email,
    password,
    setEmail,
    setPassword,
    submit,
    isSubmitting,
    errorMessage,
  } = useLoginPage();

  return (
    <div
      className={[
        "flex min-h-screen w-full flex-col items-center justify-center bg-[#f8fafc] p-2.5",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      {...props}
    >
      <form
        onSubmit={submit}
        noValidate
        className="flex w-96 shrink-0 flex-col items-start gap-6 overflow-clip bg-white p-8 ring-1 ring-[#cbd5e1] ring-inset"
      >
        {/* 브랜드. Figma Frame 8(515:58) */}
        <div className="flex shrink-0 items-center gap-2">
          <div className="relative size-12 shrink-0 overflow-hidden">
            {/* 원본 이미지에서 로고 부분만 잘라 보이게 한 Figma 배치를 그대로 옮겼다. */}
            <img
              src={goodRadarLogo}
              alt="선한레이더 로고"
              className="absolute top-[-2.05%] left-[-90.23%] h-[157.51%] w-[278.16%] max-w-none"
            />
          </div>
          <div className="flex shrink-0 flex-col items-start whitespace-nowrap font-sans text-black">
            <h1 className="mb-[-8px] shrink-0 text-lg leading-7 font-medium">
              선한레이더 관리자 페이지
            </h1>
            <p className="shrink-0 text-base leading-6 font-light">
              Good Radar
            </p>
          </div>
        </div>

        {/* 입력 칸. Figma Frame 1(515:32) */}
        <div className="flex w-80 shrink-0 flex-col items-start gap-4">
          <InputField
            label="이메일"
            name="email"
            type="email"
            inputMode="email"
            autoComplete="username"
            placeholder="이메일 입력"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <div className="flex w-full shrink-0 flex-col items-start gap-1">
            <InputField
              label="비밀번호"
              name="password"
              type="password"
              autoComplete="current-password"
              placeholder="비밀번호 입력"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            {/* 관리자 회원가입 흐름이 아직 없어 디자인 문구만 둔다. */}
            <div className="flex w-80 shrink-0 items-center justify-end">
              <span className="whitespace-nowrap font-sans text-xs leading-4 font-medium text-[#1e293b]">
                회원가입
              </span>
            </div>
          </div>
        </div>

        {/* 로그인 버튼. Figma Button(515:24) */}
        <div className="flex shrink-0 flex-col items-start gap-1">
          <button
            type="submit"
            disabled={isSubmitting}
            className="flex h-9 w-80 shrink-0 cursor-pointer items-center justify-center overflow-clip rounded-lg bg-[#3b82f6] py-2 font-sans text-sm leading-5 font-semibold whitespace-nowrap text-white disabled:cursor-default disabled:opacity-60"
          >
            로그인
          </button>
          {errorMessage && (
            <p
              role="alert"
              className="w-80 font-sans text-xs leading-4 font-medium text-[#ef4444]"
            >
              {errorMessage}
            </p>
          )}
        </div>
      </form>
    </div>
  );
}

export default LoginPage;
