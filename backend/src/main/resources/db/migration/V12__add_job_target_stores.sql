-- 조사 결과 Task 가 저장되기 전에도 대상 가게를 조회할 수 있도록 시작 당시 표시 정보를 보존한다.
-- 기존 Job 은 대상 스냅샷이 없으므로 빈 목록으로 채운다.
ALTER TABLE public.job ADD COLUMN target_stores jsonb NOT NULL DEFAULT '[]'::jsonb;
