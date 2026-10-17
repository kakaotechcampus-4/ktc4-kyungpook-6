package com.ktc4.backend.support;

import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import com.ktc4.backend.domain.member.entity.Member;

import java.util.UUID;

/**
 * 테스트용 파일 기록을 만드는 도우미. 운영 코드는 가입 신청이 가져갈 때({@code UploadFile.claim})만 기록을 만든다 —
 * 테스트는 업로드부터 거치지 않고 기록을 바로 만들어야 해서 여기 둔다. 파일 ID 는 매번 새 무작위 값이다.
 */
public final class UploadFileFixtures {

    private UploadFileFixtures() {
    }

    public static UploadFile uploadFile(Member member, UploadKind kind, FileType type, String storedName, long sizeBytes) {
        return new UploadFile(member, kind, type, storedName, sizeBytes, UUID.randomUUID().toString());
    }
}
