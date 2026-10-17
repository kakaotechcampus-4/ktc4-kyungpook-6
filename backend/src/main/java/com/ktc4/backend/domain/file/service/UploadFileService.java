package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.dto.AttachmentResponse;
import com.ktc4.backend.domain.file.dto.SignupFileIds;
import com.ktc4.backend.domain.file.dto.StoreImageResponse;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.dto.UploadResponse;
import com.ktc4.backend.domain.file.entity.PendingUpload;
import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.repository.PendingUploadRepository;
import com.ktc4.backend.domain.file.repository.UploadFileRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 올라온 파일을 저장하고 꺼낸다. 파일 내용은 디스크({@link FileStorage})에, 기록은 DB 에 둔다.
 *
 * <p>흐름은 세 단계다.
 * <ol>
 *   <li><b>올리기</b> — 점주가 가입 화면에서 사진·사업자등록증을 고를 때마다 올린다. 계정이 아직 없어
 *       {@link PendingUpload} 에 두고 무작위 파일 ID 를 돌려준다.</li>
 *   <li><b>가져가기</b> — 가입 신청이 그 파일 ID 들을 싣고 오면, 신청자의 파일({@link UploadFile})로 옮긴다.</li>
 *   <li><b>보기</b> — 관리자는 신청을 검토할 때 모든 첨부를, 승인된 가게의 점주는 그 가게의 사진을 본다.</li>
 * </ol>
 * 아무 신청도 가져가지 않은 파일은 세 시간 뒤 지운다({@link #deleteExpiredPending}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UploadFileService {

    /**
     * 아직 아무 신청도 가져가지 않은 파일을 이만큼까지만 쌓는다. 넘으면 오래된 파일이 지워질 때까지(길어야 세 시간 남짓) 업로드를 받지 않는다 —
     * 로그인 없이 부르는 API 라, 가입은 하지 않고 파일만 계속 올려 디스크를 채우는 것을 막는 마지막 방어선이다.
     * 파일 하나가 최대 10MB 라 20GB 언저리에서 멈춘다. 한 곳에서 채우지 못하게 업로드 횟수도 막는다({@code UploadRateLimitFilter}).
     */
    static final int MAX_PENDING_UPLOADS = 2000;

    private final UploadFileRepository uploadFileRepository;
    private final PendingUploadRepository pendingUploadRepository;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final StoreAccessService storeAccessService;
    private final FileStorage fileStorage;

    // ── 올리기 ───────────────────────────────────────────────────────────

    /**
     * 파일 하나를 올린다. 가입 신청 전이라 누구의 파일인지는 아직 정하지 않는다.
     *
     * <p>형식은 보낸 쪽이 적은 이름·Content-Type 이 아니라 파일 내용으로 가린다. 사진은 저장하기 전에 위치 정보 같은
     * 부가 정보를 지운다 — 디스크에 원본이 한 번도 닿지 않는다.
     *
     * <p>디스크는 DB 와 함께 되돌아가지 않는다. 디스크에 쓴 뒤 기록 저장이 실패하면 그 파일을 지운다.
     *
     * @param kind 파일의 종류
     * @param file 올라온 파일
     * @return 파일 ID 와 가린 형식
     * @throws CustomException 받을 수 없는 파일이면 {@link UploadFileValidator#validate} 와 같은 에러,
     *                         구조가 깨진 사진이면 {@code UNSUPPORTED_FILE_TYPE},
     *                         가져가지 않은 파일이 너무 많이 쌓였으면 {@code SIGNUP_TEMPORARILY_CLOSED}
     */
    @Transactional
    public UploadResponse upload(UploadKind kind, MultipartFile file) {
        FileType type = UploadFileValidator.validate(kind, file);
        long pending = pendingUploadRepository.count();
        if (pending >= MAX_PENDING_UPLOADS) {
            log.warn("파일 업로드 일시 중단 - 가져가지 않은 파일 {}건(상한 {}건)", pending, MAX_PENDING_UPLOADS);
            throw new CustomException(ErrorCode.SIGNUP_TEMPORARILY_CLOSED);
        }

        String storedName;
        long size;
        if (type.isImage()) {
            byte[] cleaned = ImageMetadataStripper.strip(type, readAll(file));
            storedName = fileStorage.save(cleaned, type);
            size = cleaned.length;
        } else {
            storedName = fileStorage.save(file, type);
            size = file.getSize();
        }
        deleteIfRolledBack(storedName);
        try {
            PendingUpload saved = pendingUploadRepository.save(
                    new PendingUpload(UUID.randomUUID().toString(), kind, type, storedName, size));
            // 파일 이름은 남기지 않는다 — 사람 이름 같은 개인정보가 들어 있을 수 있다
            log.info("파일 업로드 - kind={}, type={}, size={}", kind, type, size);
            return UploadResponse.from(saved);
        } catch (RuntimeException e) {
            fileStorage.delete(storedName);
            throw e;
        }
    }

    // 사진은 크기 상한(UploadKind.IMAGE_MAX_BYTES, 5MB)을 넘지 않는 것을 확인한 뒤라 메모리에 올려도 된다 —
    // 부가 정보를 지우는 동안 원본과 지운 결과가 함께 있어 요청 하나에 10MB 남짓이다. PDF 는 메모리에 올리지 않는다
    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("올라온 파일을 읽지 못했습니다", e);
        }
    }

    private void deleteIfRolledBack(String storedName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileStorage.delete(storedName);
                }
            }
        });
    }

    // ── 가져가기 ─────────────────────────────────────────────────────────

    /**
     * 가입 신청에 실린 파일 ID 들을 그 신청자의 파일로 옮긴다. 가입 신청과 같은 트랜잭션 안에서 부른다.
     *
     * <p>파일 ID 는 서버가 만든 무작위 값이라, 그 값을 아는 것이 곧 그 파일을 붙일 자격이다. 다음 경우는 모두
     * {@code INVALID_FILE_REFERENCE} 로 거절한다 — 하나라도 걸리면 신청 전체가 되돌아간다.
     * <ul>
     *   <li>없는 파일 ID — 지어낸 값, 세 시간이 지나 지워진 파일</li>
     *   <li>이미 다른 신청이 가져간 파일 ID — 가져가면 대기 기록이 지워져 "없는 파일"이 된다</li>
     *   <li>자리에 맞지 않는 종류 — 대표 사진 자리에 사업자등록증 ID 등</li>
     *   <li>한 신청 안에서 같은 파일 ID 를 두 자리에 쓴 경우</li>
     * </ul>
     *
     * <p>대기 기록을 잠그고 읽는다. 같은 파일 ID 로 두 신청이 동시에 들어오면 나중 것은 앞의 것이 끝난 뒤 그 기록을
     * 찾지 못한다.
     *
     * @param applicant 방금 저장된 가입 신청자
     * @param ids       가입 신청에 실린 파일 ID
     * @throws CustomException 위의 경우 {@code INVALID_FILE_REFERENCE}
     */
    @Transactional
    public void claimSignupFiles(Member applicant, SignupFileIds ids) {
        Map<String, UploadKind> expected = new LinkedHashMap<>();
        expect(expected, ids.storeImageFileId(), UploadKind.STORE_IMAGE);
        expect(expected, ids.bizCertFileId(), UploadKind.BIZ_CERT);
        for (String menuPhoto : ids.menuPhotoFileIds()) {
            expect(expected, menuPhoto, UploadKind.MENU_IMAGE);
        }
        if (expected.isEmpty()) {
            return;
        }

        List<PendingUpload> found = pendingUploadRepository.findAllForUpdateByFileKeyIn(expected.keySet());
        if (found.size() != expected.size()) {
            log.info("가입 신청 거절 - 없거나 이미 쓰인 파일 ID, 실은 {}개 중 {}개만 있음", expected.size(), found.size());
            throw new CustomException(ErrorCode.INVALID_FILE_REFERENCE);
        }
        for (PendingUpload upload : found) {
            if (upload.getKind() != expected.get(upload.getFileKey())) {
                log.info("가입 신청 거절 - 자리에 맞지 않는 파일 종류, 실은 종류={}, 자리={}",
                        upload.getKind(), expected.get(upload.getFileKey()));
                throw new CustomException(ErrorCode.INVALID_FILE_REFERENCE);
            }
        }

        uploadFileRepository.saveAll(found.stream().map(upload -> UploadFile.claim(upload, applicant)).toList());
        pendingUploadRepository.deleteAll(found);
        log.info("가입 신청 파일 연결 - memberId={}, 파일 {}개", applicant.getMemberId(), found.size());
    }

    private static void expect(Map<String, UploadKind> expected, String fileId, UploadKind kind) {
        if (fileId == null) {
            return;
        }
        if (expected.putIfAbsent(fileId, kind) != null) {
            log.info("가입 신청 거절 - 같은 파일 ID 를 두 자리에 씀");
            throw new CustomException(ErrorCode.INVALID_FILE_REFERENCE);
        }
    }

    /**
     * 정한 시각보다 먼저 올라와 아무 신청도 가져가지 않은 파일을 디스크와 함께 지운다.
     *
     * @param cutoff 이 시각보다 먼저 올라온 것만
     * @return 지운 파일 수
     */
    @Transactional
    public int deleteExpiredPending(LocalDateTime cutoff) {
        List<PendingUpload> expired = pendingUploadRepository.findByCreatedAtBefore(cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        pendingUploadRepository.deleteAll(expired);
        // 디스크 파일은 기록 삭제가 확정된 뒤 지운다 — 거꾸로 하면 기록 삭제가 되돌아갔을 때 파일 없는 기록이 남는다
        List<String> storedNames = expired.stream().map(PendingUpload::getStoredName).toList();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storedNames.forEach(fileStorage::delete);
                }
            });
        } else {
            storedNames.forEach(fileStorage::delete);
        }
        return expired.size();
    }

    // ── 보기 ─────────────────────────────────────────────────────────────

    /**
     * 가입 신청에 첨부된 파일 목록. 관리자가 신청을 검토할 때 쓴다.
     *
     * @param memberId 가입 신청한 회원 ID
     * @return 첨부 파일. 파일 업로드가 생기기 전에 가입한 점주는 빈 목록
     * @throws CustomException 점주가 아니거나 없으면 {@code MEMBER_NOT_FOUND}
     */
    public List<AttachmentResponse> getAttachments(Long memberId) {
        memberRepository.findById(memberId)
                .filter(found -> found.getRole() == MemberRole.OWNER)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        return uploadFileRepository.findByMemberMemberIdOrderByUploadFileIdAsc(memberId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    /**
     * 첨부 파일 한 개를 꺼낸다. 관리자용 — 사업자등록증까지 꺼낸다.
     *
     * @param fileId 파일 ID
     * @return 기록과 내용
     * @throws CustomException 기록이 없거나 디스크에 파일이 없으면 {@code FILE_NOT_FOUND}
     */
    public StoredFile load(String fileId) {
        UploadFile file = uploadFileRepository.findByFileKey(fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));
        return toStoredFile(file);
    }

    /**
     * 가게의 사진 목록 — 그 가게에 지금 연결된 점주가 가입할 때 올린 대표 사진·메뉴 사진.
     *
     * <p>관리자와 그 가게에 연결된 점주만 본다({@link StoreAccessService}). 체크인·가게 상세와 같은 규칙이라
     * 연결되지 않은 점주에게는 가게가 있는지도 알려 주지 않는다(없는 가게여도 403).
     *
     * @param storeId    가게 ID
     * @param authMember 로그인한 사용자
     * @return 사진. 없으면 빈 목록
     * @throws CustomException 로그인 정보가 없으면 {@code UNAUTHORIZED}, 관리자도 연결된 점주도 아니면 {@code FORBIDDEN},
     *                         관리자가 없는 가게를 부르면 {@code STORE_NOT_FOUND}
     */
    public List<StoreImageResponse> getStoreImages(Long storeId, AuthMember authMember) {
        storeAccessService.requireAccessTo(storeId, authMember);
        if (!storeRepository.existsById(storeId)) {
            throw new CustomException(ErrorCode.STORE_NOT_FOUND);
        }
        return uploadFileRepository.findOfStore(storeId, UploadKind.STORE_PHOTO_KINDS).stream()
                .map(StoreImageResponse::from)
                .toList();
    }

    /**
     * 가게 사진 한 장을 꺼낸다. 관리자는 모든 가게 사진을, 점주는 자기와 같은 가게에 연결된 점주의 사진만 꺼낸다.
     *
     * <p>사업자등록증은 이 길로 꺼낼 수 없다. 꺼낼 수 없는 파일은 "없는 파일"과 똑같이 답한다 — 다르게 답하면
     * 파일 ID 를 넣어 보며 어떤 파일이 있는지 알아낼 수 있다.
     *
     * @param fileId     파일 ID
     * @param authMember 로그인한 사용자
     * @return 기록과 내용
     * @throws CustomException 로그인 정보가 없으면 {@code UNAUTHORIZED}, 볼 수 없는 파일이면 {@code FILE_NOT_FOUND}
     */
    public StoredFile loadImage(String fileId, AuthMember authMember) {
        if (authMember == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        // 통과시킬 대상을 하나씩 적는다(StoreAccessService 와 같은 방식). 가입 상태 확인용 토큰처럼 역할이 없는 사용자는
        // 보안 설정이 먼저 막지만, 규칙이 잘못 열려도 여기서 막힌다.
        UploadFile file;
        if (authMember.role() == MemberRole.ADMIN) {
            file = uploadFileRepository.findByFileKeyAndKindIn(fileId, UploadKind.STORE_PHOTO_KINDS).orElse(null);
        } else if (authMember.role() == MemberRole.OWNER) {
            file = uploadFileRepository.findVisibleToOwner(fileId, authMember.memberId(), UploadKind.STORE_PHOTO_KINDS)
                    .orElse(null);
        } else {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        if (file == null) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }
        return toStoredFile(file);
    }

    private StoredFile toStoredFile(UploadFile file) {
        return new StoredFile(file.getFileKey(), file.getKind(), file.getFileType(), file.getSizeBytes(),
                fileStorage.load(file.getStoredName()));
    }
}
