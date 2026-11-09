package com.ktc4.backend.domain.order.entity;

import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.enums.ReceiveType;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 아동이 가게 메뉴 하나를 주문한 기록. 상태 전이(접수 대기 → 조리 중 → 전달 완료, 접수 대기에서만 취소)는 이 엔티티가 지킨다.
 *
 * <p>아동 번호는 숫자로만 둔다 — 응답에도 싣지 않고, 이 주문을 읽을 때 아동 정보가 필요한 곳이 없다. 메뉴 이름은 주문 당시 값을
 * 복사한다(메뉴 이름이 바뀌거나 메뉴를 지워도 주문은 그대로).
 *
 * <p>상태를 바꾸는 메서드는 행을 잠그고 읽은 주문에만 부른다({@code ChildOrderRepository} 의 {@code …ForUpdate}) —
 * 점주 "조리 시작"과 아동 "취소"가 동시에 와도 둘 다 접수 대기를 보고 바꾸지 않게.
 *
 * <p>시각은 생성 시각({@code createdAt}, JPA Auditing)과 같은 기준인 {@code LocalDateTime.now()}(서버 시간대)로 찍는다.
 * 앱에 줄 때 한국 시각으로 바꾸는 일은 응답({@code OrderResponse})이 한다.
 */
@Entity
@Table(name = "child_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private Long childId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "menu_id", nullable = false, updatable = false)
    private Long menuId;

    @Column(name = "menu_name", nullable = false, length = 50, updatable = false)
    private String menuName;

    @Enumerated(EnumType.STRING)
    @Column(name = "receive_type", nullable = false, length = 20, updatable = false)
    private ReceiveType receiveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "cooking_started_at")
    private LocalDateTime cookingStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private ChildOrder(Long childId, Store store, StoreMenu menu, ReceiveType receiveType) {
        this.childId = childId;
        this.store = store;
        this.menuId = menu.getMenuId();
        this.menuName = menu.getName();
        this.receiveType = receiveType;
        this.status = OrderStatus.RECEIVED;
    }

    /**
     * 접수 대기 주문을 만든다. 메뉴가 그 가게 것인지·판매 중인지는 부르는 쪽이 확인한다.
     *
     * @param childId     주문한 아동
     * @param store       가게
     * @param menu        고른 메뉴 — 이름을 복사해 둔다
     * @param receiveType 받는 방법
     * @return 저장 전 주문
     */
    public static ChildOrder receive(Long childId, Store store, StoreMenu menu, ReceiveType receiveType) {
        return new ChildOrder(childId, store, menu, receiveType);
    }

    /**
     * 조리를 시작한다 — 점주가 아이의 방문을 확인(QR·주문번호)한 뒤.
     *
     * @param now 조리 시작 시각
     * @throws CustomException 접수 대기가 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    public void startCooking(LocalDateTime now) {
        requireStatus(OrderStatus.RECEIVED);
        this.status = OrderStatus.COOKING;
        this.cookingStartedAt = now;
    }

    /**
     * 조리를 마치고 전달한다.
     *
     * @param now 완료 시각
     * @throws CustomException 조리 중이 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    public void complete(LocalDateTime now) {
        requireStatus(OrderStatus.COOKING);
        this.status = OrderStatus.DONE;
        this.completedAt = now;
    }

    /**
     * 취소한다. 조리를 시작한 뒤에는 취소할 수 없다(아동 앱·점주 앱 모두 조리 전에만 취소 버튼이 있다).
     *
     * <p>이미 취소된 주문을 다시 취소하면 아무것도 바꾸지 않고 통과한다 — 취소 버튼을 두 번 누른 두 번째 요청이 409 가 되어
     * 원하던 결과(취소됨)를 실패로 보이게 하지 않도록. 취소 시각은 처음 것을 둔다.
     *
     * @param now 취소 시각
     * @throws CustomException 접수 대기도 취소도 아니면(조리 중·전달 완료) {@code ORDER_STATUS_CONFLICT}
     */
    public void cancel(LocalDateTime now) {
        if (status == OrderStatus.CANCELED) {
            return;
        }
        requireStatus(OrderStatus.RECEIVED);
        this.status = OrderStatus.CANCELED;
        this.canceledAt = now;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new CustomException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
    }
}
