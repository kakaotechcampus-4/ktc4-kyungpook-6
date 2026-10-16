package com.ktc4.backend.domain.file.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktc4.backend.domain.file.FileIdFormat;
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
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.domain.member.service.StoreAccessService;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.global.security.AuthMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ktc4.backend.support.SignupMultipart.concat;
import static com.ktc4.backend.support.SignupMultipart.file;
import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static com.ktc4.backend.support.SignupMultipart.png;
import static com.ktc4.backend.support.UploadFileFixtures.uploadFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 기록 저장만 가짜로 두고, 디스크에는 실제로 쓴다(테스트마다 새로 만드는 임시 폴더). 계정 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadFileService")
class UploadFileServiceTest {

    private static final long MEMBER_ID = 3L;
    private static final long STORE_ID = 7L;

    @TempDir
    Path uploadDir;

    @Mock
    private UploadFileRepository uploadFileRepository;

    @Mock
    private PendingUploadRepository pendingUploadRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreAccessService storeAccessService;

    private UploadFileService service;
    private Member applicant;

    @BeforeEach
    void setUp() {
        service = new UploadFileService(uploadFileRepository, pendingUploadRepository, memberRepository,
                storeRepository, storeAccessService, new FileStorage(uploadDir.toString()));
        applicant = Member.ownerApplicant("owner@example.com", "hash",
                OwnerInfo.builder()
                        .bizNo("1234567890").storeName("예시분식").representativeName("홍길동").phone("01000000000")
                        .build());
        ReflectionTestUtils.setField(applicant, "memberId", MEMBER_ID);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private long filesOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(uploadDir)) {
            return files.count();
        }
    }

    private static ErrorCode errorCodeOf(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(CustomException.class);
        return ((CustomException) thrown).getErrorCode();
    }

    // 대기 기록 저장을 실제처럼 흉내 낸다 — 받은 것을 그대로 돌려준다
    private void givenPendingSaves() {
        when(pendingUploadRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PendingUpload savedPending() {
        ArgumentCaptor<PendingUpload> saved = ArgumentCaptor.forClass(PendingUpload.class);
        verify(pendingUploadRepository).save(saved.capture());
        return saved.getValue();
    }

    private static PendingUpload pending(UploadKind kind) {
        FileType type = kind == UploadKind.BIZ_CERT ? FileType.PDF : FileType.JPEG;
        return new PendingUpload(UUID.randomUUID().toString(), kind, type,
                UUID.randomUUID() + "." + type.extension(), 100L);
    }

    @Nested
    @DisplayName("올리기")
    class Upload {

        @Test
        @DisplayName("디스크에 쓰고 대기 기록을 남긴 뒤, 무작위 파일 ID 와 서버가 가린 형식·크기를 돌려준다")
        void storesPendingUpload() throws IOException {
            givenPendingSaves();
            MockMultipartFile bizCert = pdf("file");

            UploadResponse response = service.upload(UploadKind.BIZ_CERT, bizCert);

            PendingUpload record = savedPending();
            assertThat(response.fileId()).isEqualTo(record.getFileKey()).matches(FileIdFormat.PATTERN);
            assertThat(response.kind()).isEqualTo(UploadKind.BIZ_CERT);
            assertThat(response.fileType()).isEqualTo(FileType.PDF);
            assertThat(response.sizeBytes()).isEqualTo(bizCert.getSize());
            assertThat(filesOnDisk()).isEqualTo(1);
            // PDF 는 올라온 내용 그대로다
            assertThat(Files.readAllBytes(uploadDir.resolve(record.getStoredName()))).isEqualTo(bizCert.getBytes());
            // 바깥에 알려 주는 파일 ID 와 디스크의 이름은 다른 값이다 — 이름을 알려 주지 않는다
            assertThat(record.getStoredName()).doesNotContain(record.getFileKey());
        }

        @Test
        @DisplayName("같은 파일을 두 번 올려도 파일 ID 는 매번 다르다")
        void issuesFreshIdEachTime() {
            givenPendingSaves();

            String first = service.upload(UploadKind.STORE_IMAGE, jpeg("file")).fileId();
            String second = service.upload(UploadKind.STORE_IMAGE, jpeg("file")).fileId();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("형식은 보낸 쪽이 적은 이름·Content-Type 이 아니라 내용으로 가려 저장한다")
        void storesByDetectedType() throws IOException {
            givenPendingSaves();
            // 내용은 PNG 인데 이름과 Content-Type 은 HTML 이라고 적어 보냈다
            MockMultipartFile disguised = file("file", "page.html", "text/html", png("file").getBytes());

            UploadResponse response = service.upload(UploadKind.MENU_IMAGE, disguised);

            assertThat(response.fileType()).isEqualTo(FileType.PNG);
            assertThat(savedPending().getStoredName()).endsWith(".png").doesNotContain("page").doesNotContain("html");
        }

        @Test
        @DisplayName("사진은 위치 정보 같은 부가 정보를 지운 내용으로 저장하고, 크기도 지운 뒤의 값을 적는다 — 사업자등록증 사진도")
        void storesImagesWithoutMetadata() throws IOException {
            byte[] clean = jpeg("file").getBytes();
            // 시작 표시(2바이트) 바로 뒤에 EXIF 조각을 끼운 사진. "좌표"는 지어낸 글자다
            byte[] exifPayload = "Exif\0\0SECRET-GPS-00.0000,000.0000".getBytes(StandardCharsets.ISO_8859_1);
            byte[] withExif = concat(Arrays.copyOfRange(clean, 0, 2),
                    new byte[]{(byte) 0xFF, (byte) 0xE1, 0, (byte) (exifPayload.length + 2)}, exifPayload,
                    Arrays.copyOfRange(clean, 2, clean.length));

            for (UploadKind kind : UploadKind.values()) {
                givenPendingSaves();
                UploadResponse response = service.upload(kind, file("file", "IMG_0001.jpg", "image/jpeg", withExif));

                PendingUpload record = savedPending();
                byte[] stored = Files.readAllBytes(uploadDir.resolve(record.getStoredName()));
                assertThat(stored).as(kind.name()).isEqualTo(clean);
                assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET");
                assertThat(response.sizeBytes()).isEqualTo(clean.length);
                org.mockito.Mockito.clearInvocations(pendingUploadRepository);
            }
        }

        @Test
        @DisplayName("앞부분만 사진이고 구조가 맞지 않는 파일은 거절하고 디스크에도 기록에도 남기지 않는다")
        void rejectsMalformedImage() throws IOException {
            // 시작 표시와 조각 머리말만 있고 내용이 끊긴 파일 — 앞 12바이트만 보는 형식 확인은 통과한다
            byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

            assertThat(errorCodeOf(() -> service.upload(UploadKind.STORE_IMAGE,
                    file("file", "a.jpg", "image/jpeg", truncated))))
                    .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);

            assertThat(filesOnDisk()).isZero();
            verify(pendingUploadRepository, never()).save(any());
        }

        @Test
        @DisplayName("종류에 맞지 않는 파일은 쌓인 수를 세기 전에 거절한다 — 사진 자리에 PDF")
        void rejectsWrongTypeBeforeCounting() throws IOException {
            assertThat(errorCodeOf(() -> service.upload(UploadKind.MENU_IMAGE, pdf("file"))))
                    .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE);

            assertThat(filesOnDisk()).isZero();
            verifyNoInteractions(pendingUploadRepository);
        }

        @Test
        @DisplayName("가져가지 않은 파일이 상한까지 쌓이면 SIGNUP_TEMPORARILY_CLOSED 이고 디스크에 쓰지 않는다 — 한 건 모자라면 받는다")
        void closesWhenTooManyPending() throws IOException {
            givenPendingSaves();
            when(pendingUploadRepository.count()).thenReturn((long) UploadFileService.MAX_PENDING_UPLOADS - 1);
            service.upload(UploadKind.STORE_IMAGE, jpeg("file"));
            assertThat(filesOnDisk()).isEqualTo(1);

            when(pendingUploadRepository.count()).thenReturn((long) UploadFileService.MAX_PENDING_UPLOADS);
            assertThat(errorCodeOf(() -> service.upload(UploadKind.STORE_IMAGE, jpeg("file"))))
                    .isEqualTo(ErrorCode.SIGNUP_TEMPORARILY_CLOSED);
            assertThat(filesOnDisk()).isEqualTo(1);
        }

        @Test
        @DisplayName("기록 저장이 실패하면 디스크에 쓴 파일을 지우고 그 실패를 그대로 던진다")
        void cleansUpWhenRecordSaveFails() throws IOException {
            IllegalStateException dbFailure = new IllegalStateException("db down");
            when(pendingUploadRepository.save(any())).thenThrow(dbFailure);

            assertThat(catchThrowable(() -> service.upload(UploadKind.BIZ_CERT, pdf("file")))).isSameAs(dbFailure);
            assertThat(filesOnDisk()).isZero();
        }

        @Test
        @DisplayName("저장은 끝났는데 트랜잭션이 되돌아가면 디스크에 쓴 파일을 지운다 — 커밋되면 그대로 둔다")
        void cleansUpOnlyWhenTransactionRollsBack() throws IOException {
            givenPendingSaves();
            TransactionSynchronizationManager.initSynchronization();
            service.upload(UploadKind.STORE_IMAGE, jpeg("file"));
            List<TransactionSynchronization> committed = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();

            committed.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(filesOnDisk()).isEqualTo(1);

            TransactionSynchronizationManager.initSynchronization();
            service.upload(UploadKind.BIZ_CERT, pdf("file"));
            List<TransactionSynchronization> rolledBack = TransactionSynchronizationManager.getSynchronizations();
            assertThat(filesOnDisk()).isEqualTo(2);

            rolledBack.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(filesOnDisk()).isEqualTo(1);   // 되돌아간 쪽만 지워졌다
        }

        @Test
        @DisplayName("보낸 파일 이름은 기록에도 로그에도 남지 않는다 — 사람 이름이 들어 있을 수 있다")
        void doesNotKeepOriginalFilename() {
            givenPendingSaves();
            String filename = "홍길동_사업자등록증_원본.pdf";
            Logger logger = (Logger) LoggerFactory.getLogger(UploadFileService.class);
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.start();
            logger.addAppender(logs);
            UploadResponse response;
            try {
                response = service.upload(UploadKind.BIZ_CERT,
                        file("file", filename, "application/pdf", bytesOf(pdf("file"))));
            } finally {
                logger.detachAppender(logs);
            }

            assertThat(logs.list).isNotEmpty()
                    .noneMatch(event -> event.getFormattedMessage().contains("홍길동"))
                    .noneMatch(event -> event.getFormattedMessage().contains("사업자등록증_원본"));
            assertThat(response.toString()).doesNotContain("홍길동");
            assertThat(savedPending().getStoredName()).doesNotContain("홍길동");
        }
    }

    private static byte[] bytesOf(MockMultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("가입 신청이 가져가기")
    class Claim {

        @SuppressWarnings("unchecked")
        private List<UploadFile> claimedRecords() {
            ArgumentCaptor<Iterable<UploadFile>> saved = ArgumentCaptor.forClass(Iterable.class);
            verify(uploadFileRepository).saveAll(saved.capture());
            return (List<UploadFile>) saved.getValue();
        }

        @Test
        @DisplayName("실린 파일을 그 신청자의 파일로 옮기고 대기 기록을 지운다 — 파일 ID·디스크 이름·형식·크기는 그대로")
        void claimsAllFiles() {
            PendingUpload storeImage = pending(UploadKind.STORE_IMAGE);
            PendingUpload bizCert = pending(UploadKind.BIZ_CERT);
            PendingUpload menuPhoto = pending(UploadKind.MENU_IMAGE);
            List<PendingUpload> found = List.of(storeImage, bizCert, menuPhoto);
            when(pendingUploadRepository.findAllForUpdateByFileKeyIn(anyCollection())).thenReturn(found);

            service.claimSignupFiles(applicant, new SignupFileIds(storeImage.getFileKey(), bizCert.getFileKey(),
                    List.of(menuPhoto.getFileKey())));

            assertThat(claimedRecords())
                    .extracting(UploadFile::getFileKey, UploadFile::getKind, UploadFile::getStoredName,
                            UploadFile::getFileType, UploadFile::getSizeBytes)
                    .containsExactly(
                            tuple(storeImage.getFileKey(), UploadKind.STORE_IMAGE, storeImage.getStoredName(), FileType.JPEG, 100L),
                            tuple(bizCert.getFileKey(), UploadKind.BIZ_CERT, bizCert.getStoredName(), FileType.PDF, 100L),
                            tuple(menuPhoto.getFileKey(), UploadKind.MENU_IMAGE, menuPhoto.getStoredName(), FileType.JPEG, 100L));
            assertThat(claimedRecords()).allSatisfy(record -> assertThat(record.getMember()).isSameAs(applicant));
            verify(pendingUploadRepository).deleteAll(found);
        }

        @Test
        @DisplayName("사진 없이 사업자등록증만 실어도 된다 — 사진 없는 메뉴는 빠진다")
        void claimsBizCertOnly() {
            PendingUpload bizCert = pending(UploadKind.BIZ_CERT);
            when(pendingUploadRepository.findAllForUpdateByFileKeyIn(Set.of(bizCert.getFileKey())))
                    .thenReturn(List.of(bizCert));

            service.claimSignupFiles(applicant, new SignupFileIds(null, bizCert.getFileKey(),
                    Arrays.asList(null, null)));

            assertThat(claimedRecords()).extracting(UploadFile::getKind).containsExactly(UploadKind.BIZ_CERT);
        }

        @Test
        @DisplayName("없거나 이미 쓰인 파일 ID 가 하나라도 있으면 INVALID_FILE_REFERENCE 이고 아무것도 옮기지 않는다")
        void rejectsMissingOrUsedId() {
            PendingUpload bizCert = pending(UploadKind.BIZ_CERT);
            String gone = UUID.randomUUID().toString();   // 지어낸 값, 하루가 지나 지워진 파일, 앞의 신청이 가져간 파일
            when(pendingUploadRepository.findAllForUpdateByFileKeyIn(anyCollection())).thenReturn(List.of(bizCert));

            assertThat(errorCodeOf(() -> service.claimSignupFiles(applicant,
                    new SignupFileIds(gone, bizCert.getFileKey(), List.of()))))
                    .isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

            verify(uploadFileRepository, never()).saveAll(any());
            verify(pendingUploadRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("자리에 맞지 않는 종류면 INVALID_FILE_REFERENCE — 사업자등록증 자리에 메뉴 사진, 메뉴 사진 자리에 사업자등록증")
        void rejectsWrongKindForSlot() {
            PendingUpload menuPhoto = pending(UploadKind.MENU_IMAGE);
            PendingUpload bizCert = pending(UploadKind.BIZ_CERT);
            when(pendingUploadRepository.findAllForUpdateByFileKeyIn(anyCollection()))
                    .thenReturn(List.of(menuPhoto, bizCert));

            // 두 ID 를 자리만 바꿔 실었다
            assertThat(errorCodeOf(() -> service.claimSignupFiles(applicant,
                    new SignupFileIds(null, menuPhoto.getFileKey(), List.of(bizCert.getFileKey())))))
                    .isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

            verify(uploadFileRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("한 신청 안에서 같은 파일 ID 를 두 자리에 실으면 DB 를 보기 전에 INVALID_FILE_REFERENCE")
        void rejectsSameIdTwice() {
            String photo = UUID.randomUUID().toString();
            String bizCert = UUID.randomUUID().toString();

            assertThat(errorCodeOf(() -> service.claimSignupFiles(applicant,
                    new SignupFileIds(null, bizCert, List.of(photo, photo)))))
                    .isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);
            assertThat(errorCodeOf(() -> service.claimSignupFiles(applicant,
                    new SignupFileIds(photo, bizCert, List.of(photo)))))
                    .isEqualTo(ErrorCode.INVALID_FILE_REFERENCE);

            verifyNoInteractions(pendingUploadRepository, uploadFileRepository);
        }

        @Test
        @DisplayName("파일 ID 를 하나도 싣지 않으면 아무것도 하지 않는다")
        void nothingToClaim() {
            service.claimSignupFiles(applicant, new SignupFileIds(null, null, null));

            verifyNoInteractions(pendingUploadRepository, uploadFileRepository);
        }

        @Test
        @DisplayName("대기 기록을 잠그고 읽는다 — 같은 파일 ID 로 동시에 온 두 신청 중 하나만 가져간다")
        void readsWithLock() throws NoSuchMethodException {
            var method = PendingUploadRepository.class.getMethod("findAllForUpdateByFileKeyIn", Collection.class);

            assertThat(method.getAnnotation(org.springframework.data.jpa.repository.Lock.class).value())
                    .isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        }
    }

    @Nested
    @DisplayName("오래된 업로드 지우기")
    class Cleanup {

        private PendingUpload writtenPending() throws IOException {
            PendingUpload upload = pending(UploadKind.STORE_IMAGE);
            Files.write(uploadDir.resolve(upload.getStoredName()), jpeg("file").getBytes());
            return upload;
        }

        @Test
        @DisplayName("기한이 지난 대기 기록을 지우고, 커밋된 뒤에 디스크의 파일도 지운다")
        void deletesAfterCommit() throws IOException {
            LocalDateTime cutoff = LocalDateTime.of(2026, 10, 15, 9, 0);
            List<PendingUpload> expired = List.of(writtenPending(), writtenPending());
            when(pendingUploadRepository.findByCreatedAtBefore(cutoff)).thenReturn(expired);

            TransactionSynchronizationManager.initSynchronization();
            assertThat(service.deleteExpiredPending(cutoff)).isEqualTo(2);
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();

            verify(pendingUploadRepository).deleteAll(expired);
            assertThat(filesOnDisk()).isEqualTo(2);   // 커밋 전에는 아직 남아 있다
            syncs.forEach(TransactionSynchronization::afterCommit);
            assertThat(filesOnDisk()).isZero();
        }

        @Test
        @DisplayName("기록 삭제가 되돌아가면 디스크의 파일은 그대로 둔다 — 파일 없는 기록이 남지 않게")
        void keepsFilesWhenRolledBack() throws IOException {
            LocalDateTime cutoff = LocalDateTime.of(2026, 10, 15, 9, 0);
            when(pendingUploadRepository.findByCreatedAtBefore(cutoff)).thenReturn(List.of(writtenPending()));

            TransactionSynchronizationManager.initSynchronization();
            service.deleteExpiredPending(cutoff);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(filesOnDisk()).isEqualTo(1);
        }

        @Test
        @DisplayName("지울 것이 없으면 0 이고 아무것도 지우지 않는다")
        void nothingExpired() {
            LocalDateTime cutoff = LocalDateTime.of(2026, 10, 15, 9, 0);
            when(pendingUploadRepository.findByCreatedAtBefore(cutoff)).thenReturn(List.of());

            assertThat(service.deleteExpiredPending(cutoff)).isZero();
            verify(pendingUploadRepository, never()).deleteAll(any());
        }
    }

    @Nested
    @DisplayName("관리자가 첨부 보기")
    class AdminView {

        @Test
        @DisplayName("첨부 목록은 그 점주가 올린 파일을 올린 순서대로 준다 — 파일 ID 는 무작위 값이고, 저장한 이름은 내보내지 않는다")
        void listsAttachments() {
            UploadFile storeImage = uploadFile(applicant, UploadKind.STORE_IMAGE, FileType.JPEG, "a.jpg", 100L);
            UploadFile bizCert = uploadFile(applicant, UploadKind.BIZ_CERT, FileType.PDF, "b.pdf", 200L);
            when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(applicant));
            when(uploadFileRepository.findByMemberMemberIdOrderByUploadFileIdAsc(MEMBER_ID))
                    .thenReturn(List.of(storeImage, bizCert));

            List<AttachmentResponse> attachments = service.getAttachments(MEMBER_ID);

            assertThat(attachments).extracting(AttachmentResponse::fileId, AttachmentResponse::kind,
                            AttachmentResponse::fileType, AttachmentResponse::sizeBytes)
                    .containsExactly(
                            tuple(storeImage.getFileKey(), UploadKind.STORE_IMAGE, FileType.JPEG, 100L),
                            tuple(bizCert.getFileKey(), UploadKind.BIZ_CERT, FileType.PDF, 200L));
            assertThat(attachments).extracting(AttachmentResponse::fileId).allMatch(id -> id.matches(FileIdFormat.PATTERN));
        }

        @Test
        @DisplayName("없는 회원이나 관리자 계정의 첨부를 물으면 MEMBER_NOT_FOUND")
        void attachmentsOfNonOwner() {
            when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
            assertThat(errorCodeOf(() -> service.getAttachments(MEMBER_ID))).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

            when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(Member.admin("admin@example.com", "hash")));
            assertThat(errorCodeOf(() -> service.getAttachments(MEMBER_ID))).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("파일 ID 로 사업자등록증까지 꺼낸다 — 기록과 내용이 함께 온다")
        void loadsAnyKind() throws IOException {
            MockMultipartFile bizCert = pdf("file");
            Files.write(uploadDir.resolve("b.pdf"), bizCert.getBytes());
            UploadFile record = uploadFile(applicant, UploadKind.BIZ_CERT, FileType.PDF, "b.pdf", bizCert.getSize());
            when(uploadFileRepository.findByFileKey(record.getFileKey())).thenReturn(Optional.of(record));

            StoredFile loaded = service.load(record.getFileKey());

            assertThat(loaded.fileId()).isEqualTo(record.getFileKey());
            assertThat(loaded.kind()).isEqualTo(UploadKind.BIZ_CERT);
            assertThat(loaded.fileType()).isEqualTo(FileType.PDF);
            assertThat(loaded.sizeBytes()).isEqualTo(bizCert.getSize());
            assertThat(loaded.content().getContentAsByteArray()).isEqualTo(bizCert.getBytes());
        }

        @Test
        @DisplayName("기록이 없는 파일을 꺼내면 FILE_NOT_FOUND — 아직 아무 신청도 가져가지 않은 업로드도 같다")
        void loadsMissingRecord() {
            String unknown = UUID.randomUUID().toString();
            when(uploadFileRepository.findByFileKey(unknown)).thenReturn(Optional.empty());

            assertThat(errorCodeOf(() -> service.load(unknown))).isEqualTo(ErrorCode.FILE_NOT_FOUND);
            verifyNoInteractions(pendingUploadRepository);
        }
    }

    @Nested
    @DisplayName("가게 사진 보기")
    class StoreImages {

        private final AuthMember admin = new AuthMember(1L, MemberRole.ADMIN);
        private final AuthMember owner = new AuthMember(MEMBER_ID, MemberRole.OWNER);

        @Test
        @DisplayName("목록은 접근 확인을 먼저 하고, 가게 사진 종류만 묻고, 파일 ID 와 종류만 준다")
        void listsStoreImages() {
            UploadFile storeImage = uploadFile(applicant, UploadKind.STORE_IMAGE, FileType.JPEG, "a.jpg", 100L);
            when(storeRepository.existsById(STORE_ID)).thenReturn(true);
            when(uploadFileRepository.findOfStore(STORE_ID, UploadKind.STORE_PHOTO_KINDS)).thenReturn(List.of(storeImage));

            assertThat(service.getStoreImages(STORE_ID, owner))
                    .containsExactly(new StoreImageResponse(storeImage.getFileKey(), UploadKind.STORE_IMAGE));
            verify(storeAccessService).requireAccessTo(STORE_ID, owner);
            // 가게 사진 종류에 사업자등록증은 없다
            assertThat(UploadKind.STORE_PHOTO_KINDS).containsExactlyInAnyOrder(UploadKind.STORE_IMAGE, UploadKind.MENU_IMAGE);
        }

        @Test
        @DisplayName("연결되지 않은 점주는 가게를 읽기 전에 막힌다 — 가게가 있는지도 알려 주지 않는다")
        void unlinkedOwnerIsForbiddenFirst() {
            doThrow(new CustomException(ErrorCode.FORBIDDEN)).when(storeAccessService).requireAccessTo(STORE_ID, owner);

            assertThat(errorCodeOf(() -> service.getStoreImages(STORE_ID, owner))).isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(storeRepository, uploadFileRepository);
        }

        @Test
        @DisplayName("관리자가 없는 가게의 사진을 물으면 STORE_NOT_FOUND")
        void storeImagesOfMissingStore() {
            when(storeRepository.existsById(STORE_ID)).thenReturn(false);

            assertThat(errorCodeOf(() -> service.getStoreImages(STORE_ID, admin))).isEqualTo(ErrorCode.STORE_NOT_FOUND);
        }

        @Test
        @DisplayName("점주는 같은 가게 조건이 걸린 쿼리로만 연다 — 자기 회원 ID 로 묻고, 걸리지 않으면 없는 파일과 똑같이 FILE_NOT_FOUND")
        void ownerLoadsOnlyVisible() throws IOException {
            MockMultipartFile photo = jpeg("file");
            Files.write(uploadDir.resolve("a.jpg"), photo.getBytes());
            UploadFile record = uploadFile(applicant, UploadKind.MENU_IMAGE, FileType.JPEG, "a.jpg", photo.getSize());
            String hidden = UUID.randomUUID().toString();
            when(uploadFileRepository.findVisibleToOwner(record.getFileKey(), MEMBER_ID, UploadKind.STORE_PHOTO_KINDS))
                    .thenReturn(Optional.of(record));
            // 사업자등록증, 승인 전 신청의 사진, 다른 가게 사진, 연결이 끊긴 점주의 사진은 쿼리가 돌려주지 않는다
            when(uploadFileRepository.findVisibleToOwner(hidden, MEMBER_ID, UploadKind.STORE_PHOTO_KINDS))
                    .thenReturn(Optional.empty());

            assertThat(service.loadImage(record.getFileKey(), owner).content().getContentAsByteArray())
                    .isEqualTo(photo.getBytes());
            assertThat(errorCodeOf(() -> service.loadImage(hidden, owner))).isEqualTo(ErrorCode.FILE_NOT_FOUND);
            verify(uploadFileRepository, never()).findByFileKey(any());
        }

        @Test
        @DisplayName("관리자는 어느 가게 사진이든 열지만, 이 길로 사업자등록증은 열지 못한다")
        void adminLoadsPhotosButNotBizCert() {
            String bizCert = UUID.randomUUID().toString();
            when(uploadFileRepository.findByFileKeyAndKindIn(bizCert, UploadKind.STORE_PHOTO_KINDS))
                    .thenReturn(Optional.empty());

            assertThat(errorCodeOf(() -> service.loadImage(bizCert, admin))).isEqualTo(ErrorCode.FILE_NOT_FOUND);
            verify(uploadFileRepository, never()).findByFileKey(any());
        }

        @Test
        @DisplayName("로그인 정보가 없으면 UNAUTHORIZED, 가입 상태 확인용 토큰이면 FORBIDDEN — 둘 다 파일을 찾아보지 않는다")
        void rejectsAnonymousAndSignupStatus() {
            String fileId = UUID.randomUUID().toString();

            assertThat(errorCodeOf(() -> service.loadImage(fileId, null))).isEqualTo(ErrorCode.UNAUTHORIZED);
            assertThat(errorCodeOf(() -> service.loadImage(fileId, AuthMember.signupStatus(MEMBER_ID))))
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(uploadFileRepository);
        }
    }
}
