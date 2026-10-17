package com.ktc4.backend.domain.file.entity;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 올라왔지만 아직 어느 가입 신청에도 쓰이지 않은 파일의 기록.
 *
 * <p>점주는 가입 화면에서 사진을 고를 때마다 파일을 올리고, 마지막에 가입 신청에 그 파일 ID 들을 싣는다. 올리는 때에는
 * 계정이 아직 없어서 "누구의 파일"인지 적을 수 없다. 그래서 {@link UploadFile}(회원이 반드시 있는 기록)과 따로 둔다 —
 * 가입 신청이 이 파일을 가져가면 {@link UploadFile} 로 옮기고 이 행은 지운다. 세 시간이 지나도 아무도 가져가지 않으면
 * 디스크의 파일과 함께 지운다({@code PendingUploadCleanupScheduler}).
 *
 * <p>{@code fileKey} 는 서버가 만든 무작위 값이다. 이 값을 아는 것이 곧 그 파일을 가입 신청에 붙일 수 있는 자격이라,
 * 순번처럼 맞힐 수 있는 값을 쓰지 않는다. 한 번 가져가면 이 행이 지워지므로 같은 값으로 두 번 붙일 수 없다.
 */
@Entity
@Table(
        name = "pending_upload",
        indexes = @Index(name = "idx_pending_upload_created_at", columnList = "created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingUpload extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pending_upload_id")
    private Long pendingUploadId;

    /** 바깥에 알려 주는 파일 ID — 무작위 UUID */
    @Column(name = "file_key", nullable = false, unique = true, length = 36)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private UploadKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private FileType fileType;

    @Column(name = "stored_name", nullable = false, unique = true, length = 60)
    private String storedName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    public PendingUpload(String fileKey, UploadKind kind, FileType fileType, String storedName, long sizeBytes) {
        this.fileKey = fileKey;
        this.kind = kind;
        this.fileType = fileType;
        this.storedName = storedName;
        this.sizeBytes = sizeBytes;
    }
}
