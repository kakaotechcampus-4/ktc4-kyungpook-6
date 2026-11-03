-- 점주가 정하는 가게 운영 값을 가게에 둔다. PROMPT-185
--   · 영업 상태 방식(영업시간대로 / 직접 켜고 끄기)과 켜고 끈 값
--   · 아동 앱의 방문 조건 — 동반 인원, 식사 방법(포장·매장), 사장님 한마디
--
-- 점주 앱은 영업 상태를 앱 안에만 두고 있었다. 아동 앱이 "쉬는중"·방문 조건을 보여 주려면 서버에 있어야 한다.
-- 기본값은 점주가 정하기 전의 가장 넓은 값이다 — 영업시간대로·켜짐, 아이 본인만, 포장·매장 둘 다.
--
-- ⚠️ V5(#140)·V6(#145) 다음 번호다. 그 둘보다 먼저 배포되면 Flyway 가 옛 번호를 적용 안 된 것으로 보고 기동을 멈춘다.

ALTER TABLE store ADD COLUMN IF NOT EXISTS open_mode character varying(20) NOT NULL DEFAULT 'SCHEDULE';
ALTER TABLE store ADD COLUMN IF NOT EXISTS manual_open boolean NOT NULL DEFAULT true;
ALTER TABLE store ADD COLUMN IF NOT EXISTS companion character varying(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE store ADD COLUMN IF NOT EXISTS takeout_available boolean NOT NULL DEFAULT true;
ALTER TABLE store ADD COLUMN IF NOT EXISTS dine_in_available boolean NOT NULL DEFAULT true;
ALTER TABLE store ADD COLUMN IF NOT EXISTS owner_note character varying(100);

-- Hibernate 가 enum 칸에 만드는 허용 값 CHECK 와 같은 모양으로 건다(V1 의 다른 enum 칸과 같다)
ALTER TABLE store DROP CONSTRAINT IF EXISTS store_open_mode_check;
ALTER TABLE store ADD CONSTRAINT store_open_mode_check
    CHECK (((open_mode)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'MANUAL'::character varying])::text[])));
ALTER TABLE store DROP CONSTRAINT IF EXISTS store_companion_check;
ALTER TABLE store ADD CONSTRAINT store_companion_check
    CHECK (((companion)::text = ANY ((ARRAY['NONE'::character varying, 'ONE'::character varying, 'TWO'::character varying])::text[])));

-- 주변 가게 찾기가 위경도 사각형으로 먼저 거른다
CREATE INDEX IF NOT EXISTS idx_store_lat_lng ON store (lat, lng);
