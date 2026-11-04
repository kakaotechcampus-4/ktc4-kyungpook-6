package com.ktc4.backend.domain.order.repository;

import com.ktc4.backend.domain.order.entity.ChildOrder;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChildOrderRepository extends JpaRepository<ChildOrder, Long> {

    Optional<ChildOrder> findFirstByChildIdAndStatusIn(Long childId, Collection<OrderStatus> statuses);

    Optional<ChildOrder> findFirstByChildIdAndStore_StoreIdAndStatus(Long childId, Long storeId, OrderStatus status);

    boolean existsByStore_StoreId(Long storeId);

    /**
     * 점주 목록 — 이 가게의 주문 중 {@code since} 이후에 들어온 것과, 날짜와 상관없이 끝나지 않은 것. 최근 순.
     *
     * <p>끝나지 않은 주문을 날짜와 상관없이 싣는 이유: 아동 한 명에게 진행 중 주문은 하나뿐이라, 어제 남은 조리 중 주문이
     * 점주 화면에 안 보이면 아무도 끝낼 수 없고 그 아이는 다시 주문하지 못한다.
     *
     * @param storeId 가게 ID
     * @param since   오늘의 시작(서버 시간대로 적은 한국 00:00)
     * @param active  끝나지 않은 상태({@link OrderStatus#ACTIVE})
     * @return 주문 목록
     */
    @Query("""
            select o from ChildOrder o
            where o.store.storeId = :storeId and (o.createdAt >= :since or o.status in :active)
            order by o.orderId desc
            """)
    List<ChildOrder> findForStore(@Param("storeId") Long storeId, @Param("since") LocalDateTime since,
                                  @Param("active") Collection<OrderStatus> active);

    /**
     * 아동 본인의 주문을 잠그고 읽는다 — 상태를 보고 바꾸는 사이에 점주 요청이 끼어들지 못하게.
     *
     * <p>트랜잭션 안에서, 그 트랜잭션이 이 주문을 처음 읽는 자리에서 부른다 — 이미 읽어 둔 주문이 있으면 Hibernate 가 그 객체를
     * 돌려줘 낡은 상태를 본다({@code StoreRepository.findByIdForUpdate} 와 같은 주의). 가게까지 잠그지 않게 가게는 함께 읽지 않는다.
     *
     * @param orderId 주문 ID
     * @param childId 아동 번호 — 남의 주문이면 빈 값
     * @return 그 주문
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ChildOrder o where o.orderId = :orderId and o.childId = :childId")
    Optional<ChildOrder> findByIdAndChildIdForUpdate(@Param("orderId") Long orderId, @Param("childId") Long childId);

    /**
     * 가게의 주문을 잠그고 읽는다 — 점주 조리 시작·완료·취소에 쓴다. 주의는 {@link #findByIdAndChildIdForUpdate} 와 같다.
     *
     * @param orderId 주문 ID
     * @param storeId 가게 ID — 다른 가게 주문이면 빈 값
     * @return 그 주문
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ChildOrder o where o.orderId = :orderId and o.store.storeId = :storeId")
    Optional<ChildOrder> findByIdAndStoreIdForUpdate(@Param("orderId") Long orderId, @Param("storeId") Long storeId);

    // 관리자 목록 — 가게 이름을 줄마다 다시 조회하지 않게 가게를 함께 읽는다
    @Override
    @EntityGraph(attributePaths = "store")
    Page<ChildOrder> findAll(Pageable pageable);
}
