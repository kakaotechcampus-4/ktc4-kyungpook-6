package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.client.CallClient;
import com.ktc4.backend.domain.call.client.CallException;
import com.ktc4.backend.domain.call.client.CallNotConfigured;
import com.ktc4.backend.domain.call.repository.VerificationCallRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 로컬 callId를 전화 API의 verificationId로 바꿔 공개 로그를 즉시 조회한다. */
@Service
@RequiredArgsConstructor
public class CallLogService {

    private final VerificationCallRepository callRepository;
    private final CallClient callClient;

    public String read(Long callId, int after) {
        String externalId = callRepository.findById(callId)
                .orElseThrow(() -> new CustomException(ErrorCode.CALL_NOT_FOUND))
                .getExternalId();
        try {
            return callClient.readLogs(externalId, after);
        } catch (CallNotConfigured e) {
            throw new CustomException(ErrorCode.CALL_NOT_AVAILABLE);
        } catch (CallException e) {
            // 상위 서버 오류 본문과 통화 내용은 Backend 로그에 기록하지 않는다.
            throw new CustomException(ErrorCode.CALL_API_ERROR);
        }
    }
}
