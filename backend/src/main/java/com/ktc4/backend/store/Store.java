package com.ktc4.backend.store;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 선한영향력가게(무료급식 참여 매장).
 *
 * <p>{@code name} / {@code addressRoad} 는 화면에 그대로 보여주는 원본이고,
 * {@code nameNormalized} / {@code addressNormalized} 는 중복 판별·외부 데이터 매칭·검색에 쓰는
 * 정규화된 값이다. 두 값을 분리해 두어야 표기가 달라도 같은 가게로 인식할 수 있다.
 */
@Entity
@Table(
        name = "store",
        indexes = {
                @Index(name = "idx_store_status", columnList = "status"),
                @Index(name = "idx_store_name_normalized", columnList = "name_normalized"),
                @Index(name = "idx_store_last_checked_at", columnList = "last_checked_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    /**
     * 가게의 영업 상태.
     *
     * <p>DB 에는 이름 문자열(예: {@code "CLOSED"})로 저장한다.
     * 순서(ordinal)로 저장하면 상수 순서를 바꾸는 순간 기존 데이터의 의미가 뒤바뀌므로,
     * {@code status} 필드에 {@code @Enumerated(EnumType.STRING)} 을 반드시 붙인다.
     */
    public enum Status {

        /** 영업중 */
        OPEN,

        /** 휴업 */
        SUSPENDED,

        /** 폐업 */
        CLOSED,

        /** 미확인 — 아직 확인되지 않았거나 확인에 실패한 상태 */
        UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    /** 가게명 (화면 표시용 원본) */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 정규화된 가게명 — 공백·특수문자·법인표기를 제거한 비교용 값 */
    @Column(name = "name_normalized", nullable = false, length = 200)
    private String nameNormalized;

    /** 도로명 주소 (화면 표시용 원본) */
    @Column(name = "address_road", nullable = false, length = 500)
    private String addressRoad;

    /** 정규화된 주소 — 비교·매칭용 값 */
    @Column(name = "address_normalized", nullable = false, length = 500)
    private String addressNormalized;

    /** 위도. float 은 오차가 수십 m 라 지도 좌표에는 double 을 쓴다. */
    @Column(name = "lat")
    private Double lat;

    /** 경도 */
    @Column(name = "lng")
    private Double lng;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    /** 업종 (한식, 분식 등) */
    @Column(name = "category", length = 50)
    private String category;

    /** 전화번호 — 관리자가 확인 전화를 걸 때 사용 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 사업자등록번호 */
    @Column(name = "biz_no", length = 20)
    private String bizNo;

    /** 마지막으로 정보가 확인된 시각 */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Builder
    private Store(String name, String nameNormalized,
                  String addressRoad, String addressNormalized,
                  Double lat, Double lng,
                  Status status, String category,
                  String phone, String bizNo,
                  LocalDateTime lastCheckedAt) {
        this.name = name;
        this.nameNormalized = nameNormalized;
        this.addressRoad = addressRoad;
        this.addressNormalized = addressNormalized;
        this.lat = lat;
        this.lng = lng;
        this.status = status != null ? status : Status.UNKNOWN;
        this.category = category;
        this.phone = phone;
        this.bizNo = bizNo;
        this.lastCheckedAt = lastCheckedAt;
    }
}
