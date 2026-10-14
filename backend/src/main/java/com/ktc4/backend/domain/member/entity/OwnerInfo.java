package com.ktc4.backend.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 점주만 갖는 정보. {@link Member} 테이블에 칼럼으로 함께 저장한다(관리자는 모두 비어 있다).
 *
 * <p>가입할 때 받은 사업자 정보는 관리자가 승인 여부를 판단하는 자료다. 사업자등록번호는 다음 단계의
 * 가게 연결에 그대로 쓰도록 {@code BizNoNormalizer} 로 맞춘 10자리로 저장한다.
 * 대표자 이름과 휴대폰 번호는 개인정보라 관리자 조회에서만 내려주고 로그에는 남기지 않는다.
 *
 * <p>가게 주소·가게 전화·영업시간·메뉴는 <b>신청 당시 기록</b>이다. 가입할 때 한 번 쓰고 고치지 않으며, 관리자가
 * 통째로 읽고 승인할 때 가게에 옮겨 적는다(가게 쪽은 정규화한 표다). 그래서 영업시간·메뉴는 표로 나누지 않고
 * JSON 칸에 둔다. 네 칸 모두 {@code updatable = false} 다 — 승인·거절 때 회원 행을 다시 쓰더라도 이 칸은 건드리지
 * 않아, 옛 코드가 모르는 칸을 무시하고 읽은 기록이 다시 쓰이며 지워지지 않는다.
 * 이 칸들이 생기기 전에 가입한 점주는 비어 있다(목록은 빈 목록으로 읽는다).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerInfo {

    /** 사업자등록번호 — 하이픈 없는 숫자 10자리 */
    @Column(name = "biz_no", length = 10)
    private String bizNo;

    /** 가입할 때 입력한 상호명 */
    @Column(name = "store_name", length = 200)
    private String storeName;

    /** 대표자 이름 */
    @Column(name = "representative_name", length = 50)
    private String representativeName;

    /**
     * 점주 휴대폰 번호 — 숫자만 남긴 값. 가게를 등록할 때 적은 번호와 같으면 후보 가게를 찾는 단서가 된다.
     * 이 칸이 생기기 전에 가입한 점주는 비어 있다.
     */
    @Column(name = "phone", length = 11)
    private String phone;

    /** 관리자가 승인하거나 거절한 시각. 처리 전에는 비어 있다. */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * 가입을 거절한 관리자의 회원 ID. 거절이 아니면 비어 있다 — 승인한 관리자는 가게 연결({@link StoreOwner})에 남는다.
     */
    @Column(name = "rejected_by")
    private Long rejectedBy;

    /** 가입할 때 적은 가게 주소. 관리자가 어느 가게인지 찾는 단서이고, 가게에 옮겨 적지 않는다. */
    @Column(name = "store_address", length = 500, updatable = false)
    private String storeAddress;

    /** 가입할 때 적은 가게 전화번호(매장 유선 번호일 수 있다). 점주 휴대폰 번호({@code phone})와 다르다. */
    @Column(name = "store_phone", length = 20, updatable = false)
    private String storePhone;

    /** 가입할 때 적은 영업시간 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_business_hours", columnDefinition = "jsonb", updatable = false)
    private List<SignupBusinessHour> businessHours;

    /** 가입할 때 적은 메뉴 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "store_menus", columnDefinition = "jsonb", updatable = false)
    private List<SignupMenu> menus;

    // 사업자등록번호는 BizNoNormalizer, 휴대폰 번호는 PhoneNormalizer 로 맞춘 값을 받는다.
    // 영업시간·메뉴가 null 이면 빈 목록으로 둔다(가게 정보 없이 만드는 기존 테스트·옛 신청자와 같은 모양).
    @Builder
    private OwnerInfo(String bizNo, String storeName, String representativeName, String phone,
                      String storeAddress, String storePhone,
                      List<SignupBusinessHour> businessHours, List<SignupMenu> menus) {
        this.bizNo = bizNo;
        this.storeName = storeName;
        this.representativeName = representativeName;
        this.phone = phone;
        this.storeAddress = storeAddress;
        this.storePhone = storePhone;
        this.businessHours = businessHours == null ? List.of() : List.copyOf(businessHours);
        this.menus = menus == null ? List.of() : List.copyOf(menus);
    }

    /**
     * 가입할 때 적은 영업시간. 이 칸이 생기기 전에 가입한 점주는 빈 목록이다.
     *
     * @return 바꿀 수 없는 목록
     */
    public List<SignupBusinessHour> getBusinessHours() {
        return businessHours == null ? List.of() : List.copyOf(businessHours);
    }

    /**
     * 가입할 때 적은 메뉴. 이 칸이 생기기 전에 가입한 점주는 빈 목록이다.
     *
     * @return 바꿀 수 없는 목록
     */
    public List<SignupMenu> getMenus() {
        return menus == null ? List.of() : List.copyOf(menus);
    }

    void markReviewed(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    void markRejected(LocalDateTime rejectedAt, Long rejectedBy) {
        this.reviewedAt = rejectedAt;
        this.rejectedBy = rejectedBy;
    }

    /**
     * 처리 기록을 지운다. 거절을 되돌려 다시 승인 대기로 둘 때 쓴다 —
     * 대기 중인 신청에 "언제 누가 거절했다" 는 기록이 남아 있으면 화면이 처리된 건으로 읽는다.
     */
    void clearReview() {
        this.reviewedAt = null;
        this.rejectedBy = null;
    }
}
