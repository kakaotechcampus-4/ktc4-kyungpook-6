-- 점주가 고른 영업 상태 방식(영업시간대로 / 직접 켜고 끄기)과 켜고 끈 값을 가게에 둔다. PROMPT-185
--
-- 점주 앱은 이 값을 앱 안에만 두고 있었다. 아동 앱이 "쉬는중" 을 보여 주려면 서버에 있어야 한다.
-- 기본값은 점주 앱과 같다 — 영업시간대로, 직접 켜고 끄기로 바꾸면 켜진 채로 시작.
--
-- ⚠️ V5(#140)·V6(#145) 다음 번호다. 그 둘보다 먼저 배포되면 Flyway 가 옛 번호를 적용 안 된 것으로 보고 기동을 멈춘다.

ALTER TABLE store ADD COLUMN IF NOT EXISTS open_mode character varying(20) NOT NULL DEFAULT 'SCHEDULE';
ALTER TABLE store ADD COLUMN IF NOT EXISTS manual_open boolean NOT NULL DEFAULT true;

-- Hibernate 가 enum 칸에 만드는 허용 값 CHECK 와 같은 모양으로 건다(V1 의 다른 enum 칸과 같다)
ALTER TABLE store DROP CONSTRAINT IF EXISTS store_open_mode_check;
ALTER TABLE store ADD CONSTRAINT store_open_mode_check
    CHECK (((open_mode)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'MANUAL'::character varying])::text[])));

-- 주변 가게 찾기가 위경도 사각형으로 먼저 거른다
CREATE INDEX IF NOT EXISTS idx_store_lat_lng ON store (lat, lng);
