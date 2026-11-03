-- 점주 가입 신청에 사업자등록증의 개업일·법인명·업태·종목 칸을 더한다(PROMPT-170).
--
-- 모두 선택 칸이라 빈 값을 허용한다. 이미 가입한 점주의 행은 비어 있는 채로 둔다.
-- IF NOT EXISTS — ddl-auto 로 이 칸이 이미 생긴 DB(로컬 등)에서도 같은 결과로 지나간다.

ALTER TABLE member ADD COLUMN IF NOT EXISTS open_date date;
ALTER TABLE member ADD COLUMN IF NOT EXISTS corp_name character varying(200);
ALTER TABLE member ADD COLUMN IF NOT EXISTS business_type character varying(200);
ALTER TABLE member ADD COLUMN IF NOT EXISTS business_item character varying(200);
