import { getRequest } from './api';

/** 로그인한 사람의 권한. 관리자 웹은 ADMIN 만 쓴다. */
export type MemberRole = 'ADMIN' | 'OWNER' | 'SIGNUP_STATUS';

/** `GET /api/auth/me` — 지금 토큰의 주인. */
export type MeResponse = {
  memberId: number;
  email: string;
  role: MemberRole;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
};

export const getMe = async () => getRequest<MeResponse>('/api/auth/me');
