package com.ktc4.backend.domain.store.entity;

import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

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
                @Index(name = "idx_store_status_last_checked_at", columnList = "status, last_checked_at"),
                @Index(name = "idx_store_name_normalized", columnList = "name_normalized"),
                @Index(name = "idx_store_last_checked_at", columnList = "last_checked_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

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
    private StoreStatus status;

    /** 업종 (한식, 분식 등) */
    @Column(name = "category", length = 50)
    private String category;

    /** 전화번호 — 관리자가 확인 전화를 걸 때 사용 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 사업자등록번호 — 하이픈 없는 숫자로 정규화해 저장한다 */
    @Column(name = "biz_no", length = 20)
    private String bizNo;

    /** 마지막으로 가게 정보를 검증한 시각. 행 생성·수정 시각(createdAt/updatedAt)과는 별개다. */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Builder
    private Store(String name, String nameNormalized,
                  String addressRoad, String addressNormalized,
                  Double lat, Double lng,
                  StoreStatus status, String category,
                  String phone, String bizNo,
                  LocalDateTime lastCheckedAt) {
        this.name = name;
        this.nameNormalized = nameNormalized;
        this.addressRoad = addressRoad;
        this.addressNormalized = addressNormalized;
        this.lat = lat;
        this.lng = lng;
        this.status = status != null ? status : StoreStatus.UNKNOWN;
        this.category = category;
        this.phone = phone;
        this.bizNo = bizNo;
        this.lastCheckedAt = lastCheckedAt;
    }

    /**
     * 담당자가 수정한 기본 정보를 반영한다. 각 필드는 {@code null} 이면 바뀌지 않는다 — 부분 수정이다.
     *
     * <p>{@code name}/{@code addressRoad} 가 바뀌면 정규화 값도 함께 갱신해야 하는데, 정규화는
     * 이 엔티티가 아니라 서비스 계층의 책임이라({@code backend/docs/데이터_정규화_가이드.md})
     * 이미 정규화된 값을 호출자가 넘긴다. 원본이 {@code null} 이 아닌데 정규화 값만 {@code null} 로
     * 넘기면 안 된다 — 호출자(서비스)가 항상 짝으로 넘겨야 하는 내부 계약이다.
     *
     * <p>{@code phone} 은 다른 필드와 규칙이 다르다. 빈 문자열로 지우는 것을 허용하는 필드라
     * {@code null} 체크만 하고 빈 문자열은 그대로 통과시킨다 — "안 보냄"과 "지움"을 구분해야 한다.
     *
     * @param name              바꿀 가게명. {@code null} 이면 유지
     * @param nameNormalized    {@code name} 과 짝을 이루는 정규화 값. {@code name} 이 {@code null} 이 아니면
     *                          같이 넘겨야 한다
     * @param addressRoad       바꿀 도로명 주소. {@code null} 이면 유지
     * @param addressNormalized {@code addressRoad} 와 짝을 이루는 정규화 값. {@code addressRoad} 가
     *                          {@code null} 이 아니면 같이 넘겨야 한다
     * @param phone             바꿀 전화번호. {@code null} 이면 유지, 빈 문자열이면 지움
     * @param status            바꿀 영업 상태. {@code null} 이면 유지
     */
    public void updateBasicInfo(String name, String nameNormalized,
                                 String addressRoad, String addressNormalized,
                                 String phone, StoreStatus status) {
        if (name != null) {
            this.name = name;
            this.nameNormalized = nameNormalized;
        }
        if (addressRoad != null) {
            this.addressRoad = addressRoad;
            this.addressNormalized = addressNormalized;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (status != null) {
            this.status = status;
        }
    }

    /**
     * 담당자가 가게 정보를 직접 확인했음을 기록한다.
     *
     * <p>시각은 이 메서드가 스스로 만들지 않고 호출자(서비스)가 넘긴다 — 엔티티가 직접
     * {@code LocalDateTime.now()} 를 부르면 테스트에서 시각을 통제할 수 없다.
     *
     * @param confirmedAt 확인 완료로 기록할 시각
     */
    public void confirm(LocalDateTime confirmedAt) {
        this.lastCheckedAt = confirmedAt;
    }
}
