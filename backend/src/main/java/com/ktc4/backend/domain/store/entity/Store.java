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

    /** 가게 전화번호 — 관리자가 확인 전화를 걸 때 사용. 점주 개인 번호는 {@code ownerPhone} 에 둔다 */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * 점주 개인 휴대폰 번호 — 선한영향력가게를 신청할 때 적은 번호다. 가입 신청한 점주의 번호와 같으면
     * 후보 가게를 찾는 단서가 된다. 가게 자료를 넣을 때 명단의 개인 번호를 여기에 넣고, 자료에 없던 가게는
     * 점주 가입을 승인할 때 채워진다.
     *
     * <p>개인정보라 가게 조회 응답에는 내보내지 않는다. 가게 전화번호({@code phone})와 섞어 쓰지 않는다.
     */
    @Column(name = "owner_phone", length = 20)
    private String ownerPhone;

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
                  String phone, String ownerPhone, String bizNo,
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
        this.ownerPhone = ownerPhone;
        this.bizNo = bizNo;
        this.lastCheckedAt = lastCheckedAt;
    }

    /**
     * 점주 개인 휴대폰 번호가 비어 있을 때만 채운다. 점주 가입을 승인할 때 신청서의 번호로 부른다.
     *
     * <p>이미 값이 있으면 바꾸지 않는다 — 가게 명단에서 온 번호나 먼저 승인된 점주의 번호를,
     * 나중에 연결된 점주의 번호가 덮어쓰지 않게 하기 위해서다.
     *
     * @param ownerPhone 숫자만 남긴 휴대폰 번호. 비어 있으면 아무 일도 하지 않는다
     */
    public void recordOwnerPhoneIfAbsent(String ownerPhone) {
        if (ownerPhone == null || ownerPhone.isBlank()) {
            return;
        }
        if (this.ownerPhone == null || this.ownerPhone.isBlank()) {
            this.ownerPhone = ownerPhone;
        }
    }

    /**
     * 점주 개인 휴대폰 번호가 넘겨받은 번호와 같으면 지운다. 그 점주와의 연결을 끊을 때 부른다.
     *
     * <p>잘못 승인해서 채워진 번호가 남아 있으면, 그 번호로 가입한 사람이 계속 이 가게의 후보로 올라온다.
     * 다른 번호(가게 명단에서 온 번호, 다른 점주의 번호)는 건드리지 않는다.
     *
     * @param ownerPhone 숫자만 남긴 휴대폰 번호. 비어 있으면 아무 일도 하지 않는다
     */
    public void clearOwnerPhoneIfSame(String ownerPhone) {
        if (ownerPhone == null || ownerPhone.isBlank() || this.ownerPhone == null) {
            return;
        }
        if (this.ownerPhone.replaceAll("[^0-9]", "").equals(ownerPhone)) {
            this.ownerPhone = null;
        }
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
