package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 가게의 메뉴 한 개. 등록한 순서는 {@code menuId} 순서다.
 *
 * <p>판매 여부는 점주가 고른다(품절·잠시 중단). 가입 신청에서 옮겨 적은 메뉴는 판매 중으로 시작한다.
 *
 * <p>사진은 가입 신청 때 메뉴마다 올린 것을 승인할 때 옮겨 적는다({@code photoFileId}). 가게 관리에서 이름·판매 여부를
 * 고쳐도 그대로 남고, 가게 관리에서 새로 만든 메뉴는 사진이 없다.
 *
 * <p>지워도 행을 남기고 지운 시각만 적는다(삭제 표시). 나중의 주문이 옛 메뉴를 가리켜야 하기 때문이다.
 * 지운 메뉴는 가게 상세에 나오지 않고, 다시 살릴 수 없다 — 같은 이름으로 새로 만든다.
 *
 * <p>메뉴를 만들거나 바꾸거나 지우는 서비스는 가게를 잠근 뒤 바꾸기 전·후를 변경 기록에 남긴다
 * ({@code StoreChangeHistoryService}).
 */
@Entity
@Table(
        name = "store_menu",
        indexes = @Index(name = "idx_store_menu_store_id_menu_id", columnList = "store_id, menu_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreMenu extends BaseTimeEntity {

    /** 메뉴 이름의 최대 길이. 요청 검증({@code MenuRequest.MAX_NAME_LENGTH})과 같다. */
    public static final int MAX_NAME_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id")
    private Long menuId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /** 판매 중이면 true */
    @Column(name = "on_sale", nullable = false)
    private boolean onSale;

    /** 메뉴 사진의 파일 ID({@code upload_file.file_key}). 사진이 없으면 비어 있다 */
    @Column(name = "photo_file_id", length = 36)
    private String photoFileId;

    /** 지운 시각. 지우지 않았으면 비어 있다 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private StoreMenu(Store store, String name, boolean onSale, String photoFileId) {
        this.store = store;
        this.name = name;
        this.onSale = onSale;
        this.photoFileId = photoFileId;
    }

    /**
     * 판매 중인 메뉴를 만든다. 승인할 때 가입 신청서의 메뉴를 옮겨 적는 데 쓴다.
     *
     * @param store       가게
     * @param name        메뉴 이름
     * @param photoFileId 메뉴 사진의 파일 ID. 없으면 null
     * @return 저장 전 메뉴
     */
    public static StoreMenu onSale(Store store, String name, String photoFileId) {
        return new StoreMenu(store, name, true, photoFileId);
    }

    /**
     * 판매 여부를 정해 메뉴를 만든다. 점주가 가게 관리에서 새 메뉴를 등록할 때 쓴다.
     *
     * @param store  가게
     * @param name   메뉴 이름
     * @param onSale 판매 중이면 true
     * @return 저장 전 메뉴
     */
    public static StoreMenu of(Store store, String name, boolean onSale) {
        return new StoreMenu(store, name, onSale, null);
    }

    /**
     * 이름과 판매 여부를 바꾼다.
     *
     * @param name   메뉴 이름
     * @param onSale 판매 중이면 true
     */
    public void update(String name, boolean onSale) {
        this.name = name;
        this.onSale = onSale;
    }

    /**
     * 지운 것으로 표시한다. 행은 남는다.
     *
     * @param deletedAt 지운 시각
     */
    public void markDeleted(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
