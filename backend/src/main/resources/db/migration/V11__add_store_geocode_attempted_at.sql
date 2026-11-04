-- 주소로 좌표를 찾지 못한 마지막 시각. 좌표 채우기 배치(카카오 주소 검색)가 같은 주소를 매번 다시 묻지 않게 한다.
-- 좌표를 찾았거나 주소가 바뀌면 비운다. 이미 있는 가게는 비어 있는 채로 둔다 — 좌표가 없으면 다음 배치가 찾는다.

ALTER TABLE store ADD COLUMN IF NOT EXISTS geocode_attempted_at timestamp(6);
