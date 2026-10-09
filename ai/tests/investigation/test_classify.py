"""2차 조사 판정 — 관측값과 DB 값을 비교해 분류하는 규칙."""

from __future__ import annotations

from datetime import datetime

import pytest

from src.backend_client.models import StoreStatus
from src.investigation.classify import classify, comparison_key, domestic_phone, name_relation, road_address_key
from src.investigation.models import (
    ChangeField,
    InvestigationTarget,
    PlaceCheck,
    PlaceStatus,
    SignalType,
    TaskClassification,
)
from src.investigation.web_research import Observation, ResearchResult, Source

TARGET = InvestigationTarget(
    store_id=1,
    name="예시분식",
    address="대구광역시 중구 동성로 12",
    phone="053-111-2222",
    internal_status=StoreStatus.OPEN,
)


def obs(field: ChangeField, value: str, *domains: str, evidence: str = "근거 문장") -> Observation:
    """출처(그라운딩 검색 결과)가 붙은 관측. 도메인을 안 주면 출처 없는 관측이다."""
    return Observation(
        field=field,
        value=value,
        evidence=evidence,
        sources=tuple(Source(domain=d, url=f"https://{d}/post") for d in domains),
    )


def run(*observations: Observation):
    return classify(TARGET, ResearchResult(list(observations)))


class TestComparisonKey:
    @pytest.mark.parametrize(
        ("field", "a", "b"),
        [
            (ChangeField.PHONE, "053-111-2222", "053 111 2222"),
            (ChangeField.ADDRESS, "대구광역시 중구 동성로 12", "대구 중구 동성로12 2층"),
            (ChangeField.NAME, "예시 분식", "예시분식"),
            (ChangeField.STATUS, "closed", "CLOSED"),
        ],
    )
    def test_표기만_다르면_같은_값이다(self, field, a, b):
        assert comparison_key(field, a) == comparison_key(field, b)

    def test_띄어_쓴_도로명도_같은_주소다(self):
        """실측 회귀: '대종로 480번길 15' 와 '대종로480번길 15' 를 이전으로 셌다."""
        assert comparison_key(ChangeField.ADDRESS, "대전광역시 중구 대종로 480번길 15") == comparison_key(
            ChangeField.ADDRESS, "대전 중구 대종로480번길 15"
        )

    def test_시_이름이_도로명에_붙지_않는다(self):
        assert road_address_key("대구광역시 중구 중앙대로 397") == "중앙대로397"

    @pytest.mark.parametrize("jibun", ["대구광역시 중구 동성로3가 1-3", "대구 중구 삼덕동2가 12"])
    def test_지번_주소는_도로명_주소가_아니다(self, jibun):
        """실측 회귀: '동성로3가 1-3' 을 도로명 '동성로 3' 으로 읽었다."""
        assert road_address_key(jibun) is None

    def test_번지가_다르면_다른_주소다(self):
        assert comparison_key(ChangeField.ADDRESS, "동성로 12") != comparison_key(
            ChangeField.ADDRESS, "동성로 14"
        )


class TestDomesticPhone:
    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("053-111-2222", "053-111-2222"),
            ("0531112222", "053-111-2222"),
            ("053.111.2222", "053-111-2222"),
            ("(053) 111-2222", "053-111-2222"),
            ("전화: 053)111-2222", "053-111-2222"),
            ("02-123-4567", "02-123-4567"),
            ("02 1234 5678", "02-1234-5678"),
            ("010-1234-5678", "010-1234-5678"),
            ("070-1234-5678", "070-1234-5678"),
            ("0507-1480-0708", "0507-1480-0708"),
            ("0505-123-4567", "0505-123-4567"),
            ("+82 53-981-1021", "053-981-1021"),  # 실측: 국제 표기
            ("+82 (0)53-981-1021", "053-981-1021"),
            ("+82-10-1234-5678", "010-1234-5678"),
            ("053-111-2222 (예약)", "053-111-2222"),  # 설명이 붙어도 번호만
            ("053-111-2222, 010-1234-5678", "053-111-2222"),  # 여럿이면 첫 번째
            ("1588-1234 / 053-111-2222", "053-111-2222"),  # 대표번호는 건너뛴다
        ],
    )
    def test_번호_하나를_꺼내_국내_표기로(self, raw, expected):
        assert domestic_phone(raw) == expected

    @pytest.mark.parametrize(
        "raw",
        ["053-111-2222 (2층)", "053-111-2222(1번)", "053-111-2222 1층", "연락처 053-111-2222 12:00"],
    )
    def test_뒤에_붙은_숫자는_번호에_섞지_않는다(self, raw):
        """숫자를 자유롭게 모으면 '053-111-2222 (2층)' 이 '053-1112-2222' 가 된다 — 번호가 조용히 바뀐다."""
        assert domestic_phone(raw) == "053-111-2222"

    @pytest.mark.parametrize(
        "raw",
        [
            "1544-8855",
            "1899-0000",
            "080-123-4567",
            "0507-5256-540",
            "+82 50-7982-5941",
            "053-12-345",
            "053-111-22225",  # 끝자리가 넘친다 — 앞 네 자리만 떼어 쓰면 다른 번호가 된다
            "12345",
            "없음",
            "",
        ],
    )
    def test_가게_번호가_될_수_없으면_None(self, raw):
        assert domestic_phone(raw) is None


class TestClassify:
    def test_관측이_없으면_변화없음이고_Signal_이_없다(self):
        found = run()

        assert found.classification is TaskClassification.NO_CHANGE
        assert found.signals == []
        assert found.proposed_changes == {}

    def test_DB_값과_같으면_변화없음이고_Signal_이_없다(self):
        # 확인 근거는 이상 징후가 아니다 — Signal 로 만들지 않는다.
        found = run(
            obs(ChangeField.PHONE, "053-111-2222", "blog.naver.com"),
            obs(ChangeField.STATUS, "OPEN", "tistory.com"),
        )

        assert found.classification is TaskClassification.NO_CHANGE
        assert found.signals == []

    def test_잡힌_변화_하나가_Signal_한_행이고_출처_수를_담는다(self):
        found = run(
            obs(ChangeField.STATUS, "CLOSED", "blog.naver.com"),
            obs(ChangeField.STATUS, "CLOSED", "tistory.com"),
        )

        assert found.classification is TaskClassification.PRIORITY_CHECK
        assert found.proposed_changes == {"status": "CLOSED"}
        [signal] = found.signals
        assert (signal.signal_type, signal.field, signal.observed, signal.source_count) == (
            SignalType.SIGNAL_HIGH,
            ChangeField.STATUS,
            "CLOSED",
            2,
        )
        assert signal.evidence_url == "https://blog.naver.com/post"  # 대표 근거 하나

    def test_항목마다_Signal_이_따로다(self):
        found = run(
            obs(ChangeField.NAME, "예시김밥", "blog.naver.com"),
            obs(ChangeField.NAME, "예시 김밥", "tistory.com"),
            obs(ChangeField.PHONE, "053-999-0000", "mangoplate.com"),
        )

        assert found.proposed_changes == {"name": "예시김밥", "phone": "053-999-0000"}
        assert {s.field: s.source_count for s in found.signals} == {ChangeField.NAME: 2, ChangeField.PHONE: 1}

    def test_출처가_한_곳이어도_Signal_로_올린다(self):
        # 등급을 두지 않는다 — 담당자가 승인하므로 걸러 내지 않고, 판단 재료(출처 수)를 넘긴다.
        found = run(obs(ChangeField.PHONE, "053-999-0000", "blog.naver.com"))

        assert found.classification is TaskClassification.PRIORITY_CHECK
        assert found.signals[0].source_count == 1

    def test_같은_도메인_두_건은_출처_하나다(self):
        found = run(
            obs(ChangeField.PHONE, "053-999-0000", "blog.naver.com"),
            obs(ChangeField.PHONE, "053-999-0000", "blog.naver.com", evidence="다른 글"),
        )

        assert found.signals[0].source_count == 1

    def test_근거_한_문장을_두_검색_결과가_뒷받침하면_출처_둘이다(self):
        """구글이 한 구간에 청크 여러 개를 붙이는 경우. 서로 다른 사이트면 독립된 출처다."""
        found = run(obs(ChangeField.STATUS, "CLOSED", "blog.naver.com", "tistory.com"))

        assert found.signals[0].source_count == 2

    def test_DB_값이_맞다는_출처가_있으면_엇갈림을_표시한다(self):
        found = run(
            obs(ChangeField.STATUS, "CLOSED", "blog.naver.com"),
            obs(ChangeField.STATUS, "CLOSED", "tistory.com"),
            obs(ChangeField.STATUS, "OPEN", "mangoplate.com"),
        )

        assert found.proposed_changes == {"status": "CLOSED"}
        assert "다른 값을 가리키는 출처도 있음" in found.signals[0].evidence_text

    def test_서로_다른_새_값이_엇갈리면_출처가_많은_쪽을_제안한다(self):
        found = run(
            obs(ChangeField.ADDRESS, "대구 중구 동성로 30", "blog.naver.com"),
            obs(ChangeField.ADDRESS, "대구 중구 동성로 30", "tistory.com"),
            obs(ChangeField.ADDRESS, "대구 북구 대학로 5", "mangoplate.com"),
        )

        assert found.proposed_changes == {"addressRoad": "대구 중구 동성로 30"}
        assert found.signals[0].source_count == 2
        assert "다른 값을 가리키는 출처도 있음" in found.signals[0].evidence_text

    def test_출처가_많은_값이_뒤에_와도_그_값을_제안한다(self):
        found = run(
            obs(ChangeField.PHONE, "053-888-0000", "blog.naver.com"),
            obs(ChangeField.PHONE, "053-999-0000", "tistory.com"),
            obs(ChangeField.PHONE, "053-999-0000", "mangoplate.com"),
        )

        assert found.proposed_changes == {"phone": "053-999-0000"}
        assert found.signals[0].source_count == 2

    def test_출처가_이어지지_않은_관측만_있으면_변화가_아니다(self):
        found = run(obs(ChangeField.STATUS, "CLOSED"))

        assert found.classification is TaskClassification.NO_CHANGE
        assert found.signals == []

    def test_엇갈림이_없으면_표시도_없다(self):
        found = run(obs(ChangeField.PHONE, "053-999-0000", "blog.naver.com"))

        assert "다른 값을 가리키는 출처도 있음" not in found.signals[0].evidence_text

    def test_출처가_이어지지_않은_관측은_세지도_보여주지도_않는다(self):
        found = run(
            obs(ChangeField.STATUS, "CLOSED", "blog.naver.com"),
            obs(ChangeField.STATUS, "CLOSED"),
        )

        assert found.signals[0].source_count == 1

    def test_지번_주소_관측은_도로명_DB_주소와_비교하지_않는다(self):
        """실측 회귀(삼송빵집): 지번 주소 한 건 때문에 도로명 주소 변경이 '엇갈림'이 됐다."""
        found = run(
            obs(ChangeField.ADDRESS, "대구 중구 중앙대로 397", "blog.naver.com"),
            obs(ChangeField.ADDRESS, "대구광역시 중구 중앙대로 397", "tistory.com"),
            obs(ChangeField.ADDRESS, "대구광역시 중구 동성로3가 1-3", "mangoplate.com"),
        )

        assert found.proposed_changes == {"addressRoad": "대구 중구 중앙대로 397"}
        assert "다른 값을 가리키는 출처도 있음" not in found.signals[0].evidence_text

    def test_수정안에는_가장_흔하고_짧은_표기를_올린다(self):
        found = run(
            obs(ChangeField.ADDRESS, "대구 중구 중앙대로 397 (동성로3가 1-3)", "blog.naver.com"),
            obs(ChangeField.ADDRESS, "대구 중구 중앙대로 397", "tistory.com"),
            obs(ChangeField.ADDRESS, "대구광역시 중구 중앙대로 397", "namu.wiki"),
            obs(ChangeField.ADDRESS, "대구 중구 중앙대로 397", "mangoplate.com"),
        )

        assert found.proposed_changes == {"addressRoad": "대구 중구 중앙대로 397"}
        assert found.signals[0].evidence_url == "https://tistory.com/post"

    def test_지번_주소만_있으면_주소_판단이_없다(self):
        found = run(obs(ChangeField.ADDRESS, "대구광역시 중구 동성로3가 1-3", "blog.naver.com"))

        assert found.classification is TaskClassification.NO_CHANGE

    @pytest.mark.parametrize(
        "observed",
        ["예시분식", "예시분식 본점", "예시분식(본점)", "예시분식(例示粉食)", "예시분식 [본점]"],
    )
    def test_지점_표기만_다른_상호는_같은_이름이다(self, observed):
        target = InvestigationTarget(store_id=3, name="예시분식 본점")
        found = classify(target, ResearchResult([obs(ChangeField.NAME, observed, "blog.naver.com")]))

        assert found.classification is TaskClassification.NO_CHANGE

    @pytest.mark.parametrize("status", [StoreStatus.UNKNOWN, None])
    def test_상태를_모를_때_웹의_OPEN_은_변화가_아니다(self, status):
        """간이 DB 회귀: 운영 DB 는 상태가 전부 UNKNOWN 이라 '영업시간' 목록만 있어도 우선확인이 됐다."""
        target = InvestigationTarget(store_id=4, name="예시분식", internal_status=status)
        found = classify(
            target,
            ResearchResult(
                [
                    obs(ChangeField.STATUS, "OPEN", "siksinhot.com"),
                    obs(ChangeField.STATUS, "OPEN", "114.co.kr"),
                ]
            ),
        )

        assert found.classification is TaskClassification.NO_CHANGE
        assert found.signals == []

    def test_상태를_몰라도_폐업은_변화다(self):
        target = InvestigationTarget(store_id=5, name="예시분식", internal_status=StoreStatus.UNKNOWN)
        found = classify(
            target,
            ResearchResult(
                [
                    obs(ChangeField.STATUS, "CLOSED", "blog.naver.com"),
                    obs(ChangeField.STATUS, "CLOSED", "tistory.com"),
                ]
            ),
        )

        assert found.proposed_changes == {"status": "CLOSED"}
        assert "다른 값을 가리키는 출처도 있음" not in found.signals[0].evidence_text

    def test_상태를_몰라도_영업_출처가_있으면_폐업_변화에_엇갈림을_표시한다(self):
        """OPEN 을 변화로 세지 않아도 엇갈림 판단에서 빼면 '폐업, 엇갈림 없음'으로 보인다."""
        target = InvestigationTarget(store_id=7, name="예시분식", internal_status=StoreStatus.UNKNOWN)
        found = classify(
            target,
            ResearchResult(
                [
                    obs(ChangeField.STATUS, "CLOSED", "blog.naver.com"),
                    obs(ChangeField.STATUS, "OPEN", "siksinhot.com"),
                    obs(ChangeField.STATUS, "OPEN", "114.co.kr"),
                ]
            ),
        )

        assert found.proposed_changes == {"status": "CLOSED"}
        assert "다른 값을 가리키는 출처도 있음" in found.signals[0].evidence_text

    @pytest.mark.parametrize("phone", ["1544-8855", "1588 1234", "1899-0000"])
    def test_전국_대표번호는_가게_전화번호로_세지_않는다(self, phone):
        """간이 DB 회귀: 롯데시네마 안 매점에 영화관 대표번호를 제안했다."""
        found = run(
            obs(ChangeField.PHONE, phone, "blog.naver.com"),
            obs(ChangeField.PHONE, phone, "tistory.com"),
        )

        assert found.classification is TaskClassification.NO_CHANGE

    def test_국제_표기_전화번호는_국내_표기로_비교하고_제안한다(self):
        """간이 DB 회귀: '+82 53-981-1021' 을 053-981-1021 과 다른 번호로 셌다."""
        target = InvestigationTarget(store_id=6, name="예시분식")
        found = classify(target, ResearchResult([obs(ChangeField.PHONE, "+82 53-981-1021", "tistory.com")]))

        assert found.proposed_changes == {"phone": "053-981-1021"}

    def test_DB_번호가_국제_표기여도_같은_번호면_변화가_아니다(self):
        """DB 쪽 번호도 관측과 같은 규칙으로 비교한다 — 한쪽만 고치면 같은 번호가 '변경'이 된다."""
        target = InvestigationTarget(store_id=9, name="예시분식", phone="+82 53-111-2222")
        found = classify(target, ResearchResult([obs(ChangeField.PHONE, "053-111-2222", "tistory.com")]))

        assert found.classification is TaskClassification.NO_CHANGE

    def test_수정안_전화번호는_표준_표기다(self):
        found = run(obs(ChangeField.PHONE, "(053) 999-0000 (예약 필수)", "tistory.com"))

        assert found.proposed_changes == {"phone": "053-999-0000"}

    def test_국제_표기도_DB_번호와_같으면_변화가_아니다(self):
        found = run(obs(ChangeField.PHONE, "+82 53-111-2222", "tistory.com"))

        assert found.classification is TaskClassification.NO_CHANGE

    @pytest.mark.parametrize("phone", ["0507-5256-540", "053-12-345", "12345"])
    def test_자릿수가_맞지_않는_번호는_세지_않는다(self, phone):
        """간이 DB 회귀: 잘린 안심번호 '0507-5256-540' 을 새 번호로 제안했다."""
        found = run(obs(ChangeField.PHONE, phone, "tistory.com"))

        assert found.classification is TaskClassification.NO_CHANGE

    def test_DB_에_없는_값을_찾으면_채우자고_제안한다(self):
        target = InvestigationTarget(store_id=2, name="예시분식")
        found = classify(target, ResearchResult([obs(ChangeField.PHONE, "053-999-0000", "blog.naver.com")]))

        assert found.proposed_changes == {"phone": "053-999-0000"}

    def test_결과는_백엔드_필드명으로_직렬화된다(self):
        dumped = run(obs(ChangeField.STATUS, "CLOSED", "blog.naver.com")).model_dump(by_alias=True, mode="json")

        assert dumped["classification"] == "PRIORITY_CHECK"
        assert dumped["proposedChanges"] == {"status": "CLOSED"}
        # 백엔드 Signal 칸과 추가를 요청한 field 만 나간다. 출처 수는 칸이 없어 문구에 적힌다.
        assert set(dumped["signals"][0]) == {"signalType", "confidence", "evidenceText", "evidenceUrl", "field"}
        assert dumped["signals"][0]["field"] == "status"
        assert dumped["signals"][0]["confidence"] is None
        assert dumped["signals"][0]["evidenceText"].endswith("(출처 1곳)")
        assert "mapCheck" not in dumped


class TestMapCheck:
    """카카오맵 확인은 분류를 바꾸지 않고 Signal 도 만들지 않는다 — 결과는 mapCheck 에 따로 담긴다(응답에서는 빠진다)."""

    FOUND = PlaceCheck(status=PlaceStatus.FOUND, place_url="http://place.map.kakao.com/1")

    @pytest.mark.parametrize("status", list(PlaceStatus))
    def test_지도_확인은_Signal_을_만들지_않고_mapCheck_로_나간다(self, status):
        place = PlaceCheck(status=status)
        found = classify(TARGET, ResearchResult([]), place)

        assert found.classification is TaskClassification.NO_CHANGE
        assert found.signals == []
        assert found.map_check == place

    @pytest.mark.parametrize(
        ("field", "value"),
        [
            (ChangeField.STATUS, "CLOSED"),
            (ChangeField.ADDRESS, "대구 동구 팔공산로185길 51"),
            (ChangeField.NAME, "팔공산케이블카"),
        ],
    )
    def test_지도와_어긋나는_변화에는_다른_가게일_수_있다고_표시한다(self, field, value):
        """인허가 평가 회귀: 케이블카 안 식당에 케이블카 주소를 '이전'으로 제안했다."""
        found = classify(TARGET, ResearchResult([obs(field, value, "visitdaegu.or.kr")]), self.FOUND)

        assert found.classification is TaskClassification.PRIORITY_CHECK  # 분류는 그대로
        assert "다른 가게 정보일 수 있음" in found.signals[0].evidence_text

    def test_지도에_있는데_웹도_영업이라면_어긋나지_않는다(self):
        """DB 는 폐업인데 웹·지도 모두 영업 — 다시 연 가게다. 지도와 같은 쪽이라 표시하지 않는다."""
        target = InvestigationTarget(store_id=10, name="예시분식", internal_status=StoreStatus.CLOSED)
        found = classify(target, ResearchResult([obs(ChangeField.STATUS, "OPEN", "tistory.com")]), self.FOUND)

        assert found.proposed_changes == {"status": "OPEN"}
        assert "다른 가게 정보일 수 있음" not in found.signals[0].evidence_text

    def test_DB_가_지번뿐이면_도로명_주소는_지도와_어긋나지_않는다(self):
        """간이 DB 회귀: 지번 DB 에 도로명 주소를 채우는 것을 '다른 가게일 수 있음'으로 표시했다."""
        target = InvestigationTarget(store_id=8, name="예시분식", address="대구광역시 수성구 두산동 660")
        found = classify(
            target, ResearchResult([obs(ChangeField.ADDRESS, "대구 수성구 수성못6길 6", "tistory.com")]), self.FOUND
        )

        assert found.proposed_changes == {"addressRoad": "대구 수성구 수성못6길 6"}
        assert "다른 가게 정보일 수 있음" not in found.signals[0].evidence_text

    def test_전화번호는_지도와_어긋나지_않는다(self):
        found = classify(TARGET, ResearchResult([obs(ChangeField.PHONE, "053-999-0000", "tistory.com")]), self.FOUND)

        assert "다른 가게 정보일 수 있음" not in found.signals[0].evidence_text

    def test_지도에서_못_찾았으면_표시하지_않는다(self):
        found = classify(
            TARGET,
            ResearchResult([obs(ChangeField.NAME, "팔공산케이블카", "visitdaegu.or.kr")]),
            PlaceCheck(status=PlaceStatus.NOT_FOUND),
        )

        assert "카카오맵" not in found.signals[0].evidence_text


def dated(field: ChangeField, value: str, observed_at: str, *domains: str) -> Observation:
    return Observation(
        field=field,
        value=value,
        evidence="근거 문장",
        observed_at=observed_at,
        sources=tuple(Source(domain=d, url=f"https://{d}/post") for d in domains),
    )


class TestDates:
    """웹 근거의 게시일 — 오래된 글이 최신 DB 값을 덮지 않게 한다(네이버 블로그 실험)."""

    def test_출처_수가_같으면_최근_글의_값을_제안한다(self):
        found = run(
            dated(ChangeField.PHONE, "053-000-1111", "2019-07-20", "blog.naver.com"),
            dated(ChangeField.PHONE, "053-000-2222", "2025-03-01", "blog.naver.com"),
        )

        assert found.proposed_changes == {"phone": "053-000-2222"}

    def test_출처_수가_많은_값이_날짜보다_먼저다(self):
        found = run(
            dated(ChangeField.PHONE, "053-000-1111", "2023-01-01", "a.com", "b.com"),
            dated(ChangeField.PHONE, "053-000-2222", "2025-03-01", "c.com"),
        )

        assert found.proposed_changes == {"phone": "053-000-1111"}

    def test_확인일보다_이전_글은_판정에서_뺀다(self):
        target = TARGET.model_copy(update={"last_checked_at": datetime(2025, 10, 5)})
        found = classify(
            target, ResearchResult([dated(ChangeField.PHONE, "053-000-1111", "2024-12-01", "blog.naver.com")])
        )

        assert found.classification is TaskClassification.NO_CHANGE

    def test_확인일_뒤의_글과_날짜_모르는_글은_남긴다(self):
        target = TARGET.model_copy(update={"last_checked_at": datetime(2025, 10, 5)})
        found = classify(
            target,
            ResearchResult(
                [
                    dated(ChangeField.PHONE, "053-000-1111", "2026-01-02", "blog.naver.com"),
                    dated(ChangeField.STATUS, "CLOSED", "", "tistory.com"),
                ]
            ),
        )

        assert found.proposed_changes == {"phone": "053-000-1111", "status": "CLOSED"}

    def test_연도만_아는_글은_그해_끝으로_본다(self):
        """확인일이 2025-10-05 면 "2025" 글은 그 뒤일 수도 있어 남긴다. "2024" 는 확실히 이전이다."""
        target = TARGET.model_copy(update={"last_checked_at": datetime(2025, 10, 5)})

        def proposed(observed_at: str) -> dict:
            result = ResearchResult([dated(ChangeField.PHONE, "053-000-1111", observed_at, "a.com")])
            return classify(target, result).proposed_changes

        assert proposed("2025") == {"phone": "053-000-1111"}
        assert proposed("2024") == {}

    def test_확인일은_백엔드_필드명으로_받는다(self):
        target = InvestigationTarget.model_validate(
            {"storeId": 1, "name": "예시분식", "lastCheckedAt": "2026-09-01T10:00:00"}
        )

        assert target.last_checked_at == datetime(2026, 9, 1, 10, 0)

    def test_최근_글이_DB_값을_확인하면_더_오래된_다른_값은_올리지_않는다(self):
        found = run(
            dated(ChangeField.PHONE, "053-111-2222", "2026-09-25", "blog.naver.com"),  # DB 값
            dated(ChangeField.PHONE, "053-000-1111", "2024-12-23", "blog.naver.com"),
        )

        assert found.classification is TaskClassification.NO_CHANGE

    def test_DB_값_확인보다_새로운_다른_값은_올린다(self):
        found = run(
            dated(ChangeField.PHONE, "053-111-2222", "2024-12-23", "blog.naver.com"),  # DB 값
            dated(ChangeField.PHONE, "053-000-1111", "2026-09-25", "blog.naver.com"),
        )

        assert found.proposed_changes == {"phone": "053-000-1111"}

    def test_연도만_아는_다른_값은_그해_끝으로_보고_비교한다(self):
        """"2026" 글은 2026-03-01 확인보다 나중일 수도 있다 — 확실히 오래됐을 때만 뺀다."""
        found = run(
            dated(ChangeField.PHONE, "053-111-2222", "2026-03-01", "blog.naver.com"),  # DB 값
            dated(ChangeField.PHONE, "053-000-1111", "2026", "tistory.com"),
        )

        assert found.proposed_changes == {"phone": "053-000-1111"}

    def test_날짜_모르는_다른_값은_낡았다고_보지_않는다(self):
        found = run(
            dated(ChangeField.PHONE, "053-111-2222", "2026-09-25", "blog.naver.com"),
            dated(ChangeField.PHONE, "053-000-1111", "", "tistory.com"),
        )

        assert found.proposed_changes == {"phone": "053-000-1111"}


class TestTruncatedAddress:
    """번지까지 없는 주소는 제안하지 않는다 — 검색 요약에서 잘린 주소다."""

    JIBUN_TARGET = TARGET.model_copy(update={"address": "대구 수성구 범어동 48-1"})

    @pytest.mark.parametrize("value", ["대구광역시 수성구 범어동 3층", "대구광역시 수성구 범어동", "수성구 범어동 2층 201호"])
    def test_번지_없는_주소는_비교하지_않는다(self, value):
        found = classify(self.JIBUN_TARGET, ResearchResult([obs(ChangeField.ADDRESS, value, "a.com")]))

        assert found.classification is TaskClassification.NO_CHANGE

    @pytest.mark.parametrize("value", ["대구 수성구 동대구로80길 24 3층", "대구 수성구 수성동1가 819", "대구 수성구 만촌동 12-3"])
    def test_도로명이나_번지가_있으면_비교한다(self, value):
        found = classify(self.JIBUN_TARGET, ResearchResult([obs(ChangeField.ADDRESS, value, "a.com")]))

        assert found.proposed_changes == {"addressRoad": value}

    @pytest.mark.parametrize("value", ["만촌동 12-3", "수성동1가 819"])
    def test_시_구_없이_동과_번지만_있어도_비교한다(self, value):
        """검색 요약은 구를 빼고 동·번지만 주는 일이 흔하다 — 놓치면 바뀐 주소가 "변화없음"으로 나간다."""
        found = classify(self.JIBUN_TARGET, ResearchResult([obs(ChangeField.ADDRESS, value, "a.com")]))

        assert found.proposed_changes == {"addressRoad": value}

    @pytest.mark.parametrize("value", ["노동 3시간", "운동 10분 거리", "가동 5개월"])
    def test_동으로_끝나는_낱말은_지번_주소가_아니다(self, value):
        found = classify(self.JIBUN_TARGET, ResearchResult([obs(ChangeField.ADDRESS, value, "a.com")]))

        assert found.classification is TaskClassification.NO_CHANGE

    def test_시_도_표기만_다른_지번_주소는_같은_주소다(self):
        found = classify(
            self.JIBUN_TARGET, ResearchResult([obs(ChangeField.ADDRESS, "대구광역시 수성구 범어동 48-1", "a.com")])
        )

        assert found.classification is TaskClassification.NO_CHANGE and found.signals == []



class TestNameRelation:
    """상호 비교 — 불일치 벤치마크에서 나온 사례."""

    @pytest.mark.parametrize(
        ("db", "web"),
        [
            ("삼송빵집", "삼송빵집 본점"),
            ("은정", "은정식당"),
            ("크라운호프대구수성못점", "크라운 호프 수성못점"),  # 지점명 앞 지역어
            ("스텔라떡볶이 대구신암신천점", "스텔라떡볶이 신암신천점"),
        ],
    )
    def test_업종_지점_표기와_지역어_차이는_같은_상호다(self, db, web):
        db_key, web_key = comparison_key(ChangeField.NAME, db), comparison_key(ChangeField.NAME, web)

        assert name_relation(db_key, web_key) == "same"

    def test_잘린_이름은_판단을_보류한다(self):
        """"최과장" 은 "최과장회닾밥"(오타)도 "최과장회덮밥"(정상)도 확인해 주지 못한다."""
        assert name_relation(comparison_key(ChangeField.NAME, "최과장회닾밥"), "최과장") == "partial"

    def test_음식_이름의_지역어는_빼지_않는다(self):
        assert comparison_key(ChangeField.NAME, "대구탕집") == "대구탕집"

    @pytest.mark.parametrize("name", ["대구탕명가점", "대구왕갈비본점", "서울깍두기본점"])
    def test_지역어로_시작하는_상호는_지역어를_빼지_않는다(self, name):
        assert comparison_key(ChangeField.NAME, name) == name

    def test_잘린_이름은_오타_DB_를_확인하지도_상호_변경을_내지도_않는다(self):
        target = TARGET.model_copy(update={"name": "최과장회닾밥"})
        found = classify(target, ResearchResult([obs(ChangeField.NAME, "최과장", "a.com", "b.com")]))

        assert found.classification is TaskClassification.NO_CHANGE and found.signals == []

    def test_잘린_이름이_있어도_다른_출처의_바른_이름은_올린다(self):
        target = TARGET.model_copy(update={"name": "최과장회닾밥"})
        found = classify(
            target,
            ResearchResult([obs(ChangeField.NAME, "최과장", "a.com"), obs(ChangeField.NAME, "최과장회덮밥", "b.com")]),
        )

        assert found.proposed_changes == {"name": "최과장회덮밥"}


class TestCityInRoad:
    def test_도로명_속_광역시를_되돌려_비교한다(self):
        """모델이 "동대구로 590" 을 "동대구광역시로 590" 으로 적어 왔다(불일치 벤치마크)."""
        assert road_address_key("대구광역시 동구 동대구광역시로 590 상가") == road_address_key("대구광역시 동구 동대구로 590")

    def test_시_이름_뒤에_띄어_쓴_광역시는_그대로다(self):
        assert road_address_key("대구광역시 중구 동성로 12") == "동성로12"
