"""사업자등록번호를 얻는 경로 — `Store.bizNo`가 비어 있을 때 채운다.

    web_search  웹검색에 뭘 묻고 뭘 받을지 (프롬프트·스키마·provider 계약)
    vertex      그 provider의 Vertex 구현. 그라운딩을 쓸 수 있는 유일한 경로
    bizno       비즈노 조회 — 후보 이름·번호를 실제 사업자로 바꾼다
    matching    후보 판정 — 이름 등급과 주소 대조. 채택은 하지 않는다

측정은 `eval/biz_number.py`, 배경은 `docs/웹검색_폴백.md`.
"""
