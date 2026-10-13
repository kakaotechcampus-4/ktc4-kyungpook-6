package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.dto.AttachmentResponse;
import com.ktc4.backend.domain.file.dto.SignupFiles;
import com.ktc4.backend.domain.file.dto.StoredFile;
import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.file.repository.UploadFileRepository;
import com.ktc4.backend.domain.member.entity.Member;
import com.ktc4.backend.domain.member.enums.MemberRole;
import com.ktc4.backend.domain.member.repository.MemberRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 올라온 파일을 저장하고 꺼낸다. 파일 내용은 디스크({@link FileStorage})에, 기록은 DB 에 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UploadFileService {

    private final UploadFileRepository uploadFileRepository;
    private final MemberRepository memberRepository;
    private final FileStorage fileStorage;

    /**
     * 가입 신청에 함께 온 파일을 저장한다. 가입 신청과 같은 트랜잭션 안에서 부른다.
     *
     * <p>디스크는 DB 와 함께 되돌아가지 않는다. 그래서 파일을 쓴 뒤 가입이 실패하면 — 이 메서드 안에서든
     * 밖에서든 — 주인 없는 파일이 디스크에 남는다. 두 군데서 치운다: 저장 도중 실패하면 그 자리에서,
     * 저장은 끝났는데 트랜잭션이 되돌아가면 그때.
     *
     * @param applicant 방금 저장된 가입 신청자
     * @param files     올라온 파일
     * @throws CustomException 파일이 받을 수 없는 것이면 {@link UploadFileValidator#validate} 와 같은 에러
     */
    @Transactional
    public void storeSignupFiles(Member applicant, SignupFiles files) {
        List<String> written = new ArrayList<>();
        deleteIfRolledBack(written);
        try {
            store(applicant, UploadKind.STORE_IMAGE, files.storeImage(), written);
            for (MultipartFile menuImage : files.menuImages()) {
                store(applicant, UploadKind.MENU_IMAGE, menuImage, written);
            }
            store(applicant, UploadKind.BIZ_CERT, files.bizCert(), written);
        } catch (RuntimeException e) {
            written.forEach(fileStorage::delete);
            throw e;
        }
        log.info("가입 신청 파일 저장 - memberId={}, 파일 {}개", applicant.getMemberId(), written.size());
    }

    private void store(Member applicant, UploadKind kind, MultipartFile file, List<String> written) {
        // 컨트롤러가 이미 확인했지만 형식은 여기서 다시 가린다 — 저장하는 확장자와 내려줄 Content-Type 이 이 값이라,
        // 확인을 건너뛴 채 이 메서드를 부르는 길이 생겨도 보낸 쪽이 적은 형식으로 저장되지 않는다.
        FileType type = UploadFileValidator.validate(kind, file);
        String storedName = fileStorage.save(file, type);
        written.add(storedName);
        uploadFileRepository.save(new UploadFile(applicant, kind, type, storedName, file.getSize()));
    }

    private void deleteIfRolledBack(List<String> written) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    written.forEach(fileStorage::delete);
                }
            }
        });
    }

    /**
     * 가입 신청에 첨부된 파일 목록.
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
     * 파일 한 개를 꺼낸다.
     *
     * @param fileId 파일 ID
     * @return 기록과 내용
     * @throws CustomException 기록이 없거나 디스크에 파일이 없으면 {@code FILE_NOT_FOUND}
     */
    public StoredFile load(Long fileId) {
        UploadFile file = uploadFileRepository.findById(fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.FILE_NOT_FOUND));
        return new StoredFile(file.getUploadFileId(), file.getKind(), file.getFileType(), file.getSizeBytes(),
                fileStorage.load(file.getStoredName()));
    }
}
