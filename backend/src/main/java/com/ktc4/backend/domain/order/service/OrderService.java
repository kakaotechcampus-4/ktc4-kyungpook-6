package com.ktc4.backend.domain.order.service;

import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.order.dto.ChildOrderCreateRequest;
import com.ktc4.backend.domain.order.dto.OrderResponse;
import com.ktc4.backend.domain.order.entity.ChildOrder;
import com.ktc4.backend.domain.order.enums.OrderStatus;
import com.ktc4.backend.domain.order.repository.ChildOrderRepository;
import com.ktc4.backend.domain.store.entity.StoreMenu;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.dto.PageResponse;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import com.ktc4.backend.global.util.SeoulTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    /** 아동 한 명의 진행 중 주문을 하나로 묶는 DB 부분 unique 인덱스 이름({@code V8__create_child_order.sql}). */
    static final String ACTIVE_ORDER_INDEX = "uk_child_order_active";

    private final ChildOrderRepository childOrderRepository;
    private final StoreService storeService;
    private final StoreAccessService storeAccessService;

    /**
     * 아동이 가게 메뉴 하나를 주문한다. 접수 대기로 만든다.
     *
     * <p>진행 중(접수 대기·조리 중) 주문이 이미 있으면 거절한다. 미리 조회하지 않고 바로 넣어 DB 의 부분 unique 인덱스에 맡긴다 —
     * "있나 보고 넣기"는 주문 버튼 연타·두 기기에서 둘 다 "없음"을 보고 둘을 만든다.
     *
     * <p>점주가 연결된 가게에만 주문할 수 있다. 영업 중인지·그 받는 방법을 가게가 받는지는 아직 보지 않는다 — 그 칸(영업 상태,
     * 포장·매장)은 가게 쪽 작업이 생기면 같은 자리에서 확인한다.
     *
     * @param childId 주문하는 아동(아동 토큰에서)
     * @param request 가게·메뉴·받는 방법
     * @return 만든 주문
     * @throws CustomException 그 가게의 지우지 않은 메뉴가 아니면 {@code MENU_NOT_FOUND}, 오늘 판매하지 않으면
     *                         {@code MENU_NOT_ON_SALE}, 점주가 연결되지 않은 가게면 {@code STORE_NOT_ORDERABLE},
     *                         진행 중 주문이 있으면 {@code ACTIVE_ORDER_EXISTS}
     */
    @Transactional
    public OrderResponse create(Long childId, ChildOrderCreateRequest request) {
        StoreMenu menu = storeService.findMenu(request.storeId(), request.menuId());
        if (!menu.isOnSale()) {
            throw new CustomException(ErrorCode.MENU_NOT_ON_SALE);
        }
        // 점주가 연결되지 않은 가게의 주문은 처리할 사람이 없다 — 받으면 아이가 조리 중으로 묶이거나, 지울 수 없는 가게가 생긴다.
        // 영업 상태(주문 OFF)·포장/매장 칸이 생기면 같은 자리에서 같은 값(STORE_NOT_ORDERABLE)으로 막는다
        if (!storeAccessService.hasLinkedOwner(request.storeId())) {
            throw new CustomException(ErrorCode.STORE_NOT_ORDERABLE);
        }
        ChildOrder saved;
        try {
            saved = childOrderRepository.saveAndFlush(
                    ChildOrder.receive(childId, menu.getStore(), menu, request.receiveType()));
        } catch (DataIntegrityViolationException e) {
            // 진행 중 주문 제약만 409 로 바꾼다. 다른 제약 위반까지 바꾸면 원인을 잃으므로 그대로 던진다
            if (isActiveOrderViolation(e)) {
                throw new CustomException(ErrorCode.ACTIVE_ORDER_EXISTS);
            }
            throw e;
        }
        log.info("아동 주문 접수 - orderId={}, storeId={}", saved.getOrderId(), request.storeId());
        return OrderResponse.from(saved);
    }

    /**
     * 아동의 진행 중(접수 대기·조리 중) 주문을 찾는다. 아동 한 명에게 하나뿐이다.
     *
     * @param childId 아동 번호
     * @return 진행 중 주문. 없으면 빈 값
     */
    public Optional<OrderResponse> findActive(Long childId) {
        return childOrderRepository.findFirstByChildIdAndStatusIn(childId, OrderStatus.ACTIVE).map(OrderResponse::from);
    }

    /**
     * 아동이 자기 주문을 취소한다. 접수 대기일 때만 된다.
     *
     * @param childId 아동 번호
     * @param orderId 주문 ID
     * @return 취소한 주문
     * @throws CustomException 자기 주문이 아니면(없는 주문 포함) {@code ORDER_NOT_FOUND}, 접수 대기가 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    @Transactional
    public OrderResponse cancelByChild(Long childId, Long orderId) {
        ChildOrder order = childOrderRepository.findByIdAndChildIdForUpdate(orderId, childId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
        order.cancel(LocalDateTime.now());
        log.info("아동 주문 취소(아동) - orderId={}", orderId);
        return OrderResponse.from(order);
    }

    /**
     * 점주 주문 목록 — 이 가게의 오늘(한국 날짜) 주문과, 날짜와 상관없이 끝나지 않은 주문. 최근 순.
     *
     * @param storeId    가게 ID
     * @param authMember 로그인한 사용자
     * @return 주문 목록. 탭(상태별)·건수는 앱이 나눈다
     * @throws CustomException 관리자도 아니고 이 가게에 연결된 점주도 아니면 {@code FORBIDDEN}
     */
    public List<OrderResponse> listForStore(Long storeId, AuthMember authMember) {
        storeAccessService.requireAccessTo(storeId, authMember);
        LocalDateTime startOfToday = SeoulTime.startOfSeoulToday(Instant.now());
        return childOrderRepository.findForStore(storeId, startOfToday, OrderStatus.ACTIVE).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * 조리를 시작한다 — 점주가 아이의 방문을 QR 체크인이나 주문번호(직접 확인)로 확인한 뒤.
     *
     * @param storeId    가게 ID
     * @param orderId    주문 ID
     * @param authMember 로그인한 사용자
     * @return 조리 중이 된 주문
     * @throws CustomException 이 가게에 접근할 수 없으면 {@code FORBIDDEN}, 이 가게의 주문이 아니면 {@code ORDER_NOT_FOUND},
     *                         접수 대기가 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    @Transactional
    public OrderResponse startCooking(Long storeId, Long orderId, AuthMember authMember) {
        return changeAtStore(storeId, orderId, authMember, order -> order.startCooking(LocalDateTime.now()), "조리 시작");
    }

    /**
     * 조리를 마치고 전달한다.
     *
     * @param storeId    가게 ID
     * @param orderId    주문 ID
     * @param authMember 로그인한 사용자
     * @return 전달 완료된 주문
     * @throws CustomException 이 가게에 접근할 수 없으면 {@code FORBIDDEN}, 이 가게의 주문이 아니면 {@code ORDER_NOT_FOUND},
     *                         조리 중이 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    @Transactional
    public OrderResponse complete(Long storeId, Long orderId, AuthMember authMember) {
        return changeAtStore(storeId, orderId, authMember, order -> order.complete(LocalDateTime.now()), "전달 완료");
    }

    /**
     * 점주가 주문을 취소한다. 접수 대기일 때만 된다.
     *
     * @param storeId    가게 ID
     * @param orderId    주문 ID
     * @param authMember 로그인한 사용자
     * @return 취소한 주문
     * @throws CustomException 이 가게에 접근할 수 없으면 {@code FORBIDDEN}, 이 가게의 주문이 아니면 {@code ORDER_NOT_FOUND},
     *                         접수 대기가 아니면 {@code ORDER_STATUS_CONFLICT}
     */
    @Transactional
    public OrderResponse cancelByStore(Long storeId, Long orderId, AuthMember authMember) {
        return changeAtStore(storeId, orderId, authMember, order -> order.cancel(LocalDateTime.now()), "취소(가게)");
    }

    /**
     * 체크인한 아동이 이 가게에 낸 접수 대기 주문을 찾는다 — 점주의 QR 체크인 응답에 함께 실으려고.
     *
     * @param childId 체크인한 아동
     * @param storeId 가게 ID
     * @return 접수 대기 주문. 없으면(주문 없이 왔거나 이미 조리 중) 빈 값
     */
    public Optional<OrderResponse> findReceivedAt(Long childId, Long storeId) {
        return childOrderRepository.findFirstByChildIdAndStore_StoreIdAndStatus(childId, storeId, OrderStatus.RECEIVED)
                .map(OrderResponse::from);
    }

    /**
     * 관리자 주문 내역 — 모든 가게의 주문을 최근 순으로.
     *
     * @param page  페이지 번호(0부터)
     * @param limit 한 페이지 건수
     * @return 주문 페이지
     */
    public PageResponse<OrderResponse> listAll(int page, int limit) {
        return PageResponse.of(
                childOrderRepository.findAll(PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "orderId"))),
                OrderResponse::from);
    }

    private OrderResponse changeAtStore(Long storeId, Long orderId, AuthMember authMember,
                                        Consumer<ChildOrder> change, String action) {
        storeAccessService.requireAccessTo(storeId, authMember);
        // 이 트랜잭션에서 주문을 처음 읽는 자리에서 잠근다 — 아동 취소와 겹쳐도 한쪽만 바꾸게
        ChildOrder order = childOrderRepository.findByIdAndStoreIdForUpdate(orderId, storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORDER_NOT_FOUND));
        change.accept(order);
        log.info("아동 주문 {} - orderId={}, storeId={}", action, orderId, storeId);
        return OrderResponse.from(order);
    }

    /**
     * 진행 중 주문 제약({@value #ACTIVE_ORDER_INDEX}) 위반인지 답한다. 원인 사슬에서 Hibernate 가 알려준 제약 이름을 찾는다 —
     * 이름이 바뀌면 이 구분이 조용히 무너지므로 실제 Postgres 가 이 이름을 돌려주는지 {@code ChildOrderRepositoryTest} 가 확인한다
     * ({@code AuthService} 의 이메일 중복과 같은 방식).
     *
     * @param e 저장 중 난 제약 위반
     * @return 진행 중 주문 제약 위반이면 true
     */
    public static boolean isActiveOrderViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return ACTIVE_ORDER_INDEX.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
