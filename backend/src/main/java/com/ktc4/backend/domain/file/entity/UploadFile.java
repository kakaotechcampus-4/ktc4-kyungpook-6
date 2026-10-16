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
 * <p>바깥에는 {@code fileKey}(무작위 UUID)로만 알려 준다. 순번({@code uploadFileId})을 내보내면 번호를 바꿔 가며
 * 다른 파일을 찾아볼 수 있다. 가입 전에는 {@link PendingUpload} 에 있다가, 가입 신청이 가져가면 같은 {@code fileKey} 로
 * 이 표에 옮겨진다.
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

    /**
     * 바깥에 알려 주는 파일 ID — 무작위 UUID. 이 칸이 생기기 전(PROMPT-144)에 올라온 기록은 비어 있다.
     * DB 에서 비어 있어도 되게 둔 이유: 이미 행이 있는 표에 "비울 수 없는" 칸을 더하면 자동 스키마 갱신이 실패한다.
     */
    @Column(name = "file_key", unique = true, length = 36)
    private String fileKey;

    public UploadFile(Member member, UploadKind kind, FileType fileType, String storedName, long sizeBytes,
                      String fileKey) {
        this.member = member;
        this.kind = kind;
        this.fileType = fileType;
        this.storedName = storedName;
        this.sizeBytes = sizeBytes;
        this.fileKey = fileKey;
    }

    /**
     * 가입 신청이 가져간 파일을 그 신청자의 파일로 옮긴다. 디스크의 파일은 옮기지 않는다 — 이름이 그대로다.
     *
     * @param pending   가입 전 업로드 기록
     * @param applicant 방금 저장된 가입 신청자
     * @return 저장 전 기록
     */
    public static UploadFile claim(PendingUpload pending, Member applicant) {
        return new UploadFile(applicant, pending.getKind(), pending.getFileType(), pending.getStoredName(),
                pending.getSizeBytes(), pending.getFileKey());
    }
}
