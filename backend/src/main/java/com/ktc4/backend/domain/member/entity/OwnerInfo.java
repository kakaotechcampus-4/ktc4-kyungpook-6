package com.ktc4.backend.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 점주만 갖는 정보. {@link Member} 테이블에 칼럼으로 함께 저장한다(관리자는 모두 비어 있다).
 *
 * <p>가입할 때 받은 사업자 정보는 관리자가 승인 여부를 판단하는 자료다. 사업자등록번호는 다음 단계의
 * 가게 연결에 그대로 쓰도록 {@code BizNoNormalizer} 로 맞춘 10자리로 저장한다.
 * 대표자 이름과 휴대폰 번호는 개인정보라 관리자 조회에서만 내려주고 로그에는 남기지 않는다.
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

    /** 관리자가 승인한 시각. 승인 전에는 비어 있다. */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * @param bizNo              {@code BizNoNormalizer} 로 맞춘 사업자등록번호
     * @param storeName          상호명
     * @param representativeName 대표자 이름
     * @param phone              {@code PhoneNormalizer} 로 맞춘 휴대폰 번호
     */
    public OwnerInfo(String bizNo, String storeName, String representativeName, String phone) {
        this.bizNo = bizNo;
        this.storeName = storeName;
        this.representativeName = representativeName;
        this.phone = phone;
    }

    void markReviewed(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
