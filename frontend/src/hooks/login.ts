import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { isAxiosError } from 'axios';
import { useNavigate } from 'react-router-dom';
import { login } from '../api/generated/endpoints';
import type { ErrorResponse, LoginResponse } from '../api/generated/model';
import { saveAccessToken } from '../services/authToken';

/** 로그인에 성공하면 보내는 화면. 관리자 첫 화면인 가게 목록이다. */
const HOME_PATH = '/';

/** 응답 본문을 읽을 수 없을 때(네트워크 오류 등) 보여줄 문구. */
const FALLBACK_ERROR_MESSAGE = '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.';

/** 관리자 페이지라 점주 계정은 토큰을 받아도 들여보내지 않는다. */
const NOT_ADMIN_MESSAGE = '관리자 계정으로 로그인해 주세요.';

class NotAdminError extends Error {}

/**
 * 실패 응답에서 화면에 띄울 문구를 고른다.
 *
 * 본문 검증 실패(400)면 필드 메시지("이메일 형식이 올바르지 않습니다")가 더 구체적이라 그걸 먼저 쓰고,
 * 아니면 에러 종류 이름인 title 을 쓴다. 문구 규칙은 docs/에러_처리_가이드.md 의 "프론트가 읽는 법".
 */
export function toLoginErrorMessage(error: unknown): string {
  if (error instanceof NotAdminError) return NOT_ADMIN_MESSAGE;
  if (!isAxiosError<ErrorResponse>(error)) return FALLBACK_ERROR_MESSAGE;

  const problem = error.response?.data;
  return problem?.errors?.[0]?.message ?? problem?.title ?? FALLBACK_ERROR_MESSAGE;
}

export type UseLoginPageResult = {
  email: string;
  password: string;
  setEmail: (value: string) => void;
  setPassword: (value: string) => void;
  /** form onSubmit. 엔터로도 로그인된다. */
  submit: (event: FormEvent<HTMLFormElement>) => void;
  isSubmitting: boolean;
  /** 실패 문구. 실패 전이나 다시 시도하는 중에는 undefined 다. */
  errorMessage: string | undefined;
};

/**
 * 로그인 화면(LoginPage)의 입력값과 로그인 요청.
 *
 * 성공하면 accessToken 을 세션 스토리지에 넣는다. 이후 요청에는 api.ts 의 인터셉터가
 * Authorization 헤더를 붙인다.
 */
export function useLoginPage(): UseLoginPageResult {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const mutation = useMutation({
    mutationFn: async () => {
      const response: LoginResponse = await login({ email, password });
      if (response.role !== 'ADMIN') throw new NotAdminError();
      return response;
    },
    onSuccess: ({ accessToken }) => {
      if (accessToken) saveAccessToken(accessToken);
      navigate(HOME_PATH, { replace: true });
    },
  });

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (mutation.isPending) return;
    mutation.mutate();
  };

  return {
    email,
    password,
    setEmail,
    setPassword,
    submit,
    isSubmitting: mutation.isPending,
    errorMessage: mutation.isError ? toLoginErrorMessage(mutation.error) : undefined,
  };
}
