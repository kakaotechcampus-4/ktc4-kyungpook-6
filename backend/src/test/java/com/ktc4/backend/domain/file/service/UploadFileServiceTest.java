package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.dto.AttachmentResponse;
import com.ktc4.backend.domain.file.dto.SignupFiles;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.repository.UploadFileRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.entity.OwnerInfo;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.ktc4.backend.support.SignupMultipart.file;
import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static com.ktc4.backend.support.SignupMultipart.png;
import static com.ktc4.backend.support.SignupMultipart.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 기록 저장만 가짜로 두고, 디스크에는 실제로 쓴다(테스트마다 새로 만드는 임시 폴더). 계정 정보는 모두 가짜 값이다.
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadFileService")
class UploadFileServiceTest {

    private static final long MEMBER_ID = 3L;

    @TempDir
    Path uploadDir;

    @Mock
    private UploadFileRepository uploadFileRepository;

    @Mock
    private MemberRepository memberRepository;

    private UploadFileService service;
    private Member applicant;

    @BeforeEach
    void setUp() {
        service = new UploadFileService(uploadFileRepository, memberRepository, new FileStorage(uploadDir.toString()));
        applicant = Member.ownerApplicant("owner@example.com", "hash",
                new OwnerInfo("1234567890", "예시분식", "홍길동", "01000000000"));
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

    private List<UploadFile> savedRecords(int count) {
        ArgumentCaptor<UploadFile> saved = ArgumentCaptor.forClass(UploadFile.class);
        verify(uploadFileRepository, times(count)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    @DisplayName("대표 사진·메뉴 사진·사업자등록증을 디스크에 쓰고, 파일마다 누구의 어떤 파일인지 기록한다")
    void storesAllFilesWithRecords() throws IOException {
        MockMultipartFile storeImage = jpeg("storeImage");
        MockMultipartFile bizCert = pdf("bizCert");

        service.storeSignupFiles(applicant,
                new SignupFiles(storeImage, List.of(png("menuImages"), webp("menuImages")), bizCert));

        List<UploadFile> records = savedRecords(4);
        assertThat(records).extracting(UploadFile::getKind, UploadFile::getFileType)
                .containsExactly(
                        tuple(UploadKind.STORE_IMAGE, FileType.JPEG),
                        tuple(UploadKind.MENU_IMAGE, FileType.PNG),
                        tuple(UploadKind.MENU_IMAGE, FileType.WEBP),
                        tuple(UploadKind.BIZ_CERT, FileType.PDF));
        assertThat(records).allSatisfy(record -> assertThat(record.getMember()).isSameAs(applicant));
        assertThat(records.get(0).getSizeBytes()).isEqualTo(storeImage.getSize());
        assertThat(filesOnDisk()).isEqualTo(4);
        // 기록의 이름으로 디스크에서 찾으면 올린 내용 그대로다
        assertThat(Files.readAllBytes(uploadDir.resolve(records.get(3).getStoredName()))).isEqualTo(bizCert.getBytes());
    }

    @Test
    @DisplayName("메뉴 사진 없이도 저장한다")
    void storesWithoutMenuImages() throws IOException {
        service.storeSignupFiles(applicant, new SignupFiles(jpeg("storeImage"), null, pdf("bizCert")));

        assertThat(savedRecords(2)).extracting(UploadFile::getKind)
                .containsExactly(UploadKind.STORE_IMAGE, UploadKind.BIZ_CERT);
        assertThat(filesOnDisk()).isEqualTo(2);
    }

    @Test
    @DisplayName("형식은 보낸 쪽이 적은 이름·Content-Type 이 아니라 내용으로 가려 저장한다")
    void storesByDetectedType() throws IOException {
        // 내용은 PNG 인데 이름과 Content-Type 은 HTML 이라고 적어 보냈다
        MockMultipartFile disguised = file("storeImage", "page.html", "text/html", png("storeImage").getBytes());

        service.storeSignupFiles(applicant, new SignupFiles(disguised, List.of(), pdf("bizCert")));

        UploadFile record = savedRecords(2).get(0);
        assertThat(record.getFileType()).isEqualTo(FileType.PNG);
        assertThat(record.getStoredName()).endsWith(".png").doesNotContain("page").doesNotContain("html");
    }

    @Test
    @DisplayName("받을 수 없는 파일이 섞여 있으면 거절하고, 그 앞에 디스크에 쓴 파일도 지운다")
    void cleansUpWhenLaterFileIsRejected() throws IOException {
        // 대표 사진은 정상이라 먼저 디스크에 쓰인다. 사업자등록증 자리에 사진이 왔다
        SignupFiles files = new SignupFiles(jpeg("storeImage"), List.of(png("menuImages")), jpeg("bizCert"));

        assertThatThrownBy(() -> service.storeSignupFiles(applicant, files))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));

        assertThat(filesOnDisk()).isZero();
    }

    @Test
    @DisplayName("기록 저장이 실패하면 디스크에 쓴 파일을 지우고 그 실패를 그대로 던진다")
    void cleansUpWhenRecordSaveFails() throws IOException {
        IllegalStateException dbFailure = new IllegalStateException("db down");
        when(uploadFileRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(dbFailure);

        Throwable thrown = catchThrowable(() -> service.storeSignupFiles(applicant,
                new SignupFiles(jpeg("storeImage"), List.of(), pdf("bizCert"))));

        assertThat(thrown).isSameAs(dbFailure);
        assertThat(filesOnDisk()).isZero();
    }

    @Test
    @DisplayName("저장은 끝났는데 트랜잭션이 되돌아가면 디스크에 쓴 파일을 지운다 — 커밋되면 그대로 둔다")
    void cleansUpOnlyWhenTransactionRollsBack() throws IOException {
        TransactionSynchronizationManager.initSynchronization();
        service.storeSignupFiles(applicant, new SignupFiles(jpeg("storeImage"), List.of(), pdf("bizCert")));
        List<TransactionSynchronization> committed = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();

        committed.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertThat(filesOnDisk()).isEqualTo(2);

        TransactionSynchronizationManager.initSynchronization();
        service.storeSignupFiles(applicant, new SignupFiles(jpeg("storeImage"), List.of(), pdf("bizCert")));
        List<TransactionSynchronization> rolledBack = TransactionSynchronizationManager.getSynchronizations();
        assertThat(filesOnDisk()).isEqualTo(4);

        rolledBack.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(filesOnDisk()).isEqualTo(2);   // 되돌아간 쪽의 두 개만 지워졌다
    }

    @Test
    @DisplayName("첨부 목록은 그 점주가 올린 파일을 올린 순서대로 준다 — 저장한 이름은 내보내지 않는다")
    void listsAttachments() {
        UploadFile storeImage = new UploadFile(applicant, UploadKind.STORE_IMAGE, FileType.JPEG, "a.jpg", 100L);
        UploadFile bizCert = new UploadFile(applicant, UploadKind.BIZ_CERT, FileType.PDF, "b.pdf", 200L);
        ReflectionTestUtils.setField(storeImage, "uploadFileId", 11L);
        ReflectionTestUtils.setField(bizCert, "uploadFileId", 12L);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(applicant));
        when(uploadFileRepository.findByMemberMemberIdOrderByUploadFileIdAsc(MEMBER_ID))
                .thenReturn(List.of(storeImage, bizCert));

        List<AttachmentResponse> attachments = service.getAttachments(MEMBER_ID);

        assertThat(attachments).extracting(AttachmentResponse::fileId, AttachmentResponse::kind,
                        AttachmentResponse::fileType, AttachmentResponse::sizeBytes)
                .containsExactly(
                        tuple(11L, UploadKind.STORE_IMAGE, FileType.JPEG, 100L),
                        tuple(12L, UploadKind.BIZ_CERT, FileType.PDF, 200L));
    }

    @Test
    @DisplayName("없는 회원이나 관리자 계정의 첨부를 물으면 MEMBER_NOT_FOUND")
    void attachmentsOfNonOwner() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAttachments(MEMBER_ID))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));

        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(Member.admin("admin@example.com", "hash")));
        assertThatThrownBy(() -> service.getAttachments(MEMBER_ID))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("파일 한 개를 꺼내면 기록과 내용이 함께 온다")
    void loadsFile() throws IOException {
        MockMultipartFile bizCert = pdf("bizCert");
        service.storeSignupFiles(applicant, new SignupFiles(jpeg("storeImage"), List.of(), bizCert));
        UploadFile record = savedRecords(2).get(1);
        ReflectionTestUtils.setField(record, "uploadFileId", 12L);
        when(uploadFileRepository.findById(12L)).thenReturn(Optional.of(record));

        StoredFile loaded = service.load(12L);

        assertThat(loaded.fileId()).isEqualTo(12L);
        assertThat(loaded.kind()).isEqualTo(UploadKind.BIZ_CERT);
        assertThat(loaded.fileType()).isEqualTo(FileType.PDF);
        assertThat(loaded.sizeBytes()).isEqualTo(bizCert.getSize());
        assertThat(loaded.content().getContentAsByteArray()).isEqualTo(bizCert.getBytes());
    }

    @Test
    @DisplayName("기록이 없는 파일을 꺼내면 FILE_NOT_FOUND")
    void loadsMissingRecord() {
        when(uploadFileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.load(99L))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND));
    }
}
