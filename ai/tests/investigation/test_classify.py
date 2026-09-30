"""2차 조사 판정 — 관측값과 DB 값을 비교해 분류하는 규칙."""

from __future__ import annotations

import pytest

from src.backend_client.models import StoreStatus
from src.investigation.classify import classify, comparison_key, domestic_phone, road_address_key
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
        assert found.evidences == []

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
        assert set(dumped["signals"][0]) == {
            "signalType", "field", "observed", "evidenceText", "evidenceUrl", "sourceCount",
        }


class TestMapCheck:
    """카카오맵 확인은 분류를 바꾸지 않고 Signal 도 만들지 않는다 — 결과는 mapCheck 로 따로 나간다."""

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
