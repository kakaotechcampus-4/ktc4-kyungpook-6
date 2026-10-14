package com.ktc4.backend.domain.file.entity;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 올라온 파일 한 개의 기록. 파일 내용은 디스크에 있고, 여기에는 누구의 어떤 파일인지와 디스크에서 찾을 이름만 둔다.
 *
 * <p>보낸 쪽이 붙인 원래 파일 이름은 저장하지 않는다. 사람 이름 같은 개인정보가 들어 있을 수 있고,
 * 경로 문자({@code ../})가 섞여 있을 수 있어 디스크 경로로도 쓰지 않는다.
 */
@Entity
@Table(
        name = "upload_file",
        indexes = @Index(name = "idx_upload_file_member_id", columnList = "member_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadFile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_file_id")
    private Long uploadFileId;

    /** 파일을 올린 회원 — 가입 신청자 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private UploadKind kind;

    /** 서버가 파일 앞부분으로 가린 형식. 내려줄 때의 Content-Type 은 이 값으로 정한다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private FileType fileType;

    /** 디스크에 저장한 이름 — 서버가 만든 무작위 값에 확장자를 붙인 것 */
    @Column(name = "stored_name", nullable = false, unique = true, length = 60)
    private String storedName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    public UploadFile(Member member, UploadKind kind, FileType fileType, String storedName, long sizeBytes) {
        this.member = member;
        this.kind = kind;
        this.fileType = fileType;
        this.storedName = storedName;
        this.sizeBytes = sizeBytes;
    }
}
