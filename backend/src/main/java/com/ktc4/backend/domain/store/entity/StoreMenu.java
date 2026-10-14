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

/**
 * 가게의 메뉴 한 개. 등록한 순서는 {@code menuId} 순서다.
 *
 * <p>판매 여부는 점주가 고른다(품절·잠시 중단). 처음 만들 때는 판매 중이다.
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

    private StoreMenu(Store store, String name, boolean onSale) {
        this.store = store;
        this.name = name;
        this.onSale = onSale;
    }

    /**
     * 판매 중인 메뉴를 만든다.
     *
     * @param store 가게
     * @param name  메뉴 이름
     * @return 저장 전 메뉴
     */
    public static StoreMenu onSale(Store store, String name) {
        return new StoreMenu(store, name, true);
    }
}
