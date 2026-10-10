"""목업 가게를 백엔드 DB 에 직접 넣는다 — **로컬 검증용**.

백엔드에 **가게를 만드는 API 가 없다.** 시드도 없다(`AdminAccountInitializer` 는 관리자
계정만 만든다). 그래서 연동 흐름을 돌려 보려면 DB 에 직접 넣는 수밖에 없다.

⚠️ **로컬 compose 전용이다.** 운영·dev 서버에 쓰지 말 것 — 가게 데이터는 그쪽에서
관리자가 넣을 일이고, 이 스크립트는 자동 증가 시퀀스를 건드린다.

compose 의 postgres 는 **호스트로 포트를 열지 않는다.** 그래서 기본은 SQL 을 표준출력으로
내보내고, `docker exec` 로 흘려 넣는다. `--dsn` 을 주면 직접 접속한다(포트를 연 환경용).

    uv run python -m eval.seed_backend --csv eval/fixtures/mock_nts_checks.csv \
        | docker exec -i ktc4-postgres psql -U ktc4 -d ktc4
"""

from __future__ import annotations

import argparse
import csv
import sys
from datetime import datetime
from pathlib import Path


def rows(path: Path, limit: int) -> list[dict]:
    with path.open(encoding="utf-8-sig") as f:
        return [{k: (v or None) for k, v in r.items()} for r in list(csv.DictReader(f))[:limit]]


def _lit(v) -> str:
    """SQL 리터럴. 작은따옴표를 겹쳐 막는다 — 가게 이름에 들어 있다."""
    if v is None or v == "":
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"


def to_sql(data: list[dict]) -> str:
    """`docker exec -i ... psql` 로 흘려 넣을 SQL. 포트를 열지 않은 compose 에서 쓴다."""
    out = ["BEGIN;"]
    for r in data:
        out.append(
            # created_at·updated_at 은 JPA Auditing 이 채우는 칸이라 DB 기본값이 없다.
            # SQL 로 직접 넣을 때는 우리가 채워야 한다.
            "INSERT INTO store (store_id,name,name_normalized,address_road,address_normalized,"
            "lat,lng,status,category,phone,biz_no,created_at,updated_at) VALUES ("
            f"{r['storeId']},{_lit(r['name'])},{_lit(r['nameNormalized'])},{_lit(r['addressRoad'])},"
            f"{_lit(r['addressNormalized'])},{r['lat'] or 'NULL'},{r['lng'] or 'NULL'},"
            f"{_lit(r['internalStatus'] or 'UNKNOWN')},{_lit(r['category'])},{_lit(r['phone'])},"
            f"{_lit(r['bizNo'])},now(),now()) ON CONFLICT (store_id) DO NOTHING;"
        )
        out.append(
            "INSERT INTO store_nts_check (store_id,biz_no,check_result,nts_state,nts_closed_at,"
            "last_attempt_at,last_success_at,created_at,updated_at) VALUES ("
            f"{r['storeId']},{_lit(r['bizNo'])},{_lit(r['ntsLookup'])},{_lit(r['ntsStatus'])},NULL,"
            "now(),now(),now(),now()) ON CONFLICT (store_id) DO NOTHING;"
        )
    # 자동 증가 시퀀스를 넣은 id 뒤로 민다. 안 하면 다음 INSERT 가 충돌한다.
    out.append("SELECT setval(pg_get_serial_sequence('store','store_id'),"
               "(SELECT COALESCE(MAX(store_id),1) FROM store));")
    out.append("COMMIT;")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", type=Path, required=True)
    ap.add_argument("--dsn", help="주면 직접 접속한다. 없으면 SQL 을 표준출력으로 낸다")
    ap.add_argument("--limit", type=int, default=60)
    args = ap.parse_args()

    data_rows = rows(args.csv, args.limit)
    if not args.dsn:
        print(to_sql(data_rows))
        return 0

    try:
        import psycopg
    except ImportError:
        print("psycopg 가 필요합니다: uv run --with 'psycopg[binary]' python -m eval.seed_backend ...",
              file=sys.stderr)
        return 1

    data = data_rows
    now = datetime.now()
    with psycopg.connect(args.dsn) as conn, conn.cursor() as cur:
        for r in data:
            # status 는 StoreStatus 상수 이름이다. 목업은 전부 UNKNOWN(담당자가 아직 안 봄).
            cur.execute(
                """
                INSERT INTO store (store_id, name, name_normalized, address_road,
                                   address_normalized, lat, lng, status, category, phone, biz_no)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT (store_id) DO NOTHING
                """,
                (int(r["storeId"]), r["name"], r["nameNormalized"], r["addressRoad"],
                 r["addressNormalized"], r["lat"], r["lng"], r["internalStatus"] or "UNKNOWN",
                 r["category"], r["phone"], r["bizNo"]),
            )
            cur.execute(
                """
                INSERT INTO store_nts_check (store_id, biz_no, check_result, nts_state,
                                             nts_closed_at, last_attempt_at, last_success_at)
                VALUES (%s,%s,%s,%s,NULL,%s,%s)
                ON CONFLICT (store_id) DO NOTHING
                """,
                (int(r["storeId"]), r["bizNo"], r["ntsLookup"], r["ntsStatus"], now, now),
            )
        # 자동 증가 시퀀스를 넣은 id 뒤로 밀어 둔다. 안 하면 다음 INSERT 가 충돌한다.
        cur.execute("SELECT setval(pg_get_serial_sequence('store','store_id'), "
                    "(SELECT COALESCE(MAX(store_id),1) FROM store))")
        conn.commit()
    print(f"가게 {len(data)}건 넣었습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
