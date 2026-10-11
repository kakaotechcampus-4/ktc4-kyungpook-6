package com.ktc4.backend.domain.store.repository;

import com.ktc4.backend.domain.store.dto.StoreWithNtsCheck;
import com.ktc4.backend.domain.store.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /**
     * 가게와 국세청 확인 기록을 함께 읽는다.
     *
     * <p>아직 확인되지 않은 가게도 빠지지 않도록 바깥 조인으로 읽는다 — 그런 가게는 기록이 null 이다.
     *
     * @param pageable 페이지 정보
     * @return 가게별 확인 기록. 기록이 없으면 {@code check} 가 null
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            left join StoreNtsCheck c on c.store = s
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findAllWithNtsCheck(Pageable pageable);

    /**
     * 고른 가게들과 그 국세청 확인 기록을 함께 읽는다 — 조사 대상을 나눌 때 쓴다.
     *
     * <p>아직 확인되지 않은 가게도 빠지지 않도록 바깥 조인으로 읽는다. 없는 가게 번호는 결과에 없다.
     *
     * @param storeIds 읽을 가게 번호. 비어 있으면 안 된다
     * @return storeId 오름차순의 가게별 확인 기록. 기록이 없으면 {@code check} 가 null
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            left join StoreNtsCheck c on c.store = s
            where s.storeId in :storeIds
            order by s.storeId asc
            """)
    List<StoreWithNtsCheck> findWithNtsCheckByStoreIdIn(@Param("storeIds") Collection<Long> storeIds);

    /**
     * 우리 DB 상태와 국세청 상태가 서로 다른 가게만 읽는다 — 1차 조사(국세청 대조)로 수정안이 나오는 대상이다.
     *
     * <p>거르는 규칙은 {@code StatusComparison.isMismatch()} 와 같아야 한다. 국세청 미등록은
     * 상태가 다른 게 아니라 번호가 틀린 것이라 제외하고({@code findDataProblem} 이 맡는다),
     * 우리 상태가 UNKNOWN 이거나 국세청 상태를 한 번도 확인하지 못한 가게도 비교할 수 없으므로 제외한다.
     *
     * <p>이번 회차 조회가 실패한(UNCONFIRMED) 가게는 거르지 않는다 — 마지막으로 확인한 {@code ntsState} 가
     * 남아 있고 응답의 {@code statusMismatch} 도 그 값으로 계산한다. 여기서 거르면 국세청이 하루 실패한
     * 것만으로 조사 대상이 목록에서 빠진다.
     *
     * <p>번호가 지워진(NO_BIZ_NO) 가게는 제외한다 — 남아 있는 {@code ntsState} 는 옛 번호 기준이라
     * 다른 사업자의 상태일 수 있다. 번호부터 찾아야 하므로 {@code findDataProblem} 이 맡고,
     * 번호를 찾으면 다음 배치가 새 번호로 확인해 이 목록에 들어온다.
     *
     * @param pageable 페이지 정보
     * @return 상태가 다른 가게 목록
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            join StoreNtsCheck c on c.store = s
            where c.ntsState is not null
              and c.checkResult <> com.ktc4.backend.domain.store.enums.NtsLookupResult.NO_BIZ_NO
              and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.NOT_REGISTERED
              and s.status <> com.ktc4.backend.domain.store.enums.StoreStatus.UNKNOWN
              and ((s.status = com.ktc4.backend.domain.store.enums.StoreStatus.OPEN
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.ACTIVE)
                or (s.status = com.ktc4.backend.domain.store.enums.StoreStatus.SUSPENDED
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.SUSPENDED)
                or (s.status = com.ktc4.backend.domain.store.enums.StoreStatus.CLOSED
                        and c.ntsState <> com.ktc4.backend.domain.business.enums.BusinessState.CLOSED))
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findStatusMismatch(Pageable pageable);

    /**
     * 우리 DB 의 사업자등록번호가 없거나 틀린 가게만 읽는다 — 번호부터 찾거나 바로잡아야 하는 대상이다.
     *
     * <p>조회 실패(UNCONFIRMED)는 다시 조회하면 확인될 수 있어 데이터 문제로 보지 않는다.
     *
     * @param pageable 페이지 정보
     * @return 번호가 없거나(NO_BIZ_NO) 국세청에 없는(NOT_REGISTERED) 가게 목록
     */
    @Query("""
            select new com.ktc4.backend.domain.store.dto.StoreWithNtsCheck(s, c)
            from Store s
            join StoreNtsCheck c on c.store = s
            where c.checkResult = com.ktc4.backend.domain.store.enums.NtsLookupResult.NO_BIZ_NO
               or c.ntsState = com.ktc4.backend.domain.business.enums.BusinessState.NOT_REGISTERED
            order by s.storeId asc
            """)
    Page<StoreWithNtsCheck> findDataProblem(Pageable pageable);

    /**
     * 점주 가입 신청에 연결할 후보 가게를 읽는다 — 사업자등록번호나 점주 휴대폰 번호가 같거나, 이름이 겹치는 가게다.
     *
     * <p>휴대폰 번호는 가게 전화번호({@code phone})가 아니라 점주 개인 번호({@code owner_phone})와 비교한다.
     * 가게 전화번호는 매장 번호라 신청서의 개인 번호와 같을 이유가 없다.
     *
     * <p>사업자등록번호가 같은 가게를 맨 앞에, 휴대폰 번호가 같은 가게를 그다음에, 이름만 겹치는 가게를 뒤에 둔다.
     * 앞의 둘은 값이 정확히 같아야 하는 단서고, 이름은 일부만 겹쳐도 되는 약한 단서다. 이름 안에서는
     * 정확히 같은 가게를 일부만 겹치는 가게보다 앞에 둔다 — 흔한 이름이면 겹치는 가게가 많아 건수 제한에 잘린다.
     *
     * <p>가게의 번호들은 숫자만 남겨 비교한다. 가게 데이터에는 하이픈·공백이 섞인 표기가 있을 수 있다.
     * 그래서 JPQL 이 아니라 Postgres 의 {@code regexp_replace} 를 쓰는 네이티브 질의다.
     *
     * <p>찾지 않을 단서에는 어떤 가게와도 맞지 않는 값을 넘긴다({@code StoreService.NO_MATCH}).
     * 빈 문자열을 넘기면 안 된다 — 번호 칸이 비어 있는 가게, 이름에서는 모든 가게와 맞는다.
     *
     * @param bizNo          하이픈 없는 숫자 10자리
     * @param phone          숫자만 남긴 신청서의 휴대폰 번호
     * @param nameNormalized {@code StoreNormalizer.normalizeName} 으로 맞춘 이름. 한글·영문·숫자만 남은 값이라
     *                       LIKE 특수문자가 없다
     * @param limit          최대 건수
     * @return 사업자등록번호 일치, 휴대폰 번호 일치, 이름 일치, 이름 일부 겹침 순서. 같은 단서 안에서는 storeId 오름차순
     */
    @Query(value = """
            select s.*
            from store s
            where regexp_replace(s.biz_no, '[^0-9]', '', 'g') = :bizNo
               or regexp_replace(s.owner_phone, '[^0-9]', '', 'g') = :phone
               or s.name_normalized like concat('%', :nameNormalized, '%')
            order by case
                         when regexp_replace(s.biz_no, '[^0-9]', '', 'g') = :bizNo then 0
                         when regexp_replace(s.owner_phone, '[^0-9]', '', 'g') = :phone then 1
                         when s.name_normalized = :nameNormalized then 2
                         else 3
                     end,
                     s.store_id asc
            limit :limit
            """, nativeQuery = true)
    List<Store> findOwnerCandidates(@Param("bizNo") String bizNo,
                                    @Param("phone") String phone,
                                    @Param("nameNormalized") String nameNormalized,
                                    @Param("limit") int limit);
}
