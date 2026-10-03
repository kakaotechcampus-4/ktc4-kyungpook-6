package com.ktc4.backend.domain.checkin.service;

import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.qr.dto.QrResolution;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckInService {

    private final CheckInRepository checkInRepository;
    private final StoreService storeService;
    private final QrCredentialService qrCredentialService;

    /**
     * 점주가 스캔한 아동 QR 로 체크인을 기록한다.
     *
     * <p>가게를 QR 보다 먼저 확인한다. 둘 다 틀린 요청에 어떤 에러가 나갈지를 하나로 고정하기 위해서다.
     * QR 형식은 모르고 {@link QrCredentialService#resolveChildId} 에만 묻는다.
     *
     * <p>QR 이 맞지 않으면 {@code storeId} 와 실패 이유를 INFO 로그로 남긴다. 응답은 이유와 관계없이 같다.
     *
     * <p>⚠️ 점주 로그인은 생겼지만 점주 ↔ 가게 연결이 아직 없어서, 로그인한 점주라면 아무 {@code storeId} 로나
     * 기록할 수 있다. 연결이 생기면 로그인한 점주의 가게인지 확인해야 한다.
     *
     * @param storeId   체크인할 가게 ID
     * @param qrPayload 점주 앱이 스캔한 QR 문자열
     * @return 체크인 기록 ID 와 시각. 아동 정보는 담지 않는다
     * @throws CustomException 가게가 없으면 {@code STORE_NOT_FOUND}, QR 이 맞지 않으면 {@code INVALID_QR_TOKEN}
     */
    @Transactional
    public CheckInResponse checkIn(Long storeId, String qrPayload) {
        Store store = storeService.findStore(storeId);
        Long childId = switch (qrCredentialService.resolveChildId(qrPayload)) {
            case QrResolution.Resolved resolved -> resolved.childId();
            case QrResolution.Rejected rejected -> {
                // 응답은 이유와 관계없이 하나지만, 디버깅을 위해 이유는 남긴다. QR 문자열·토큰·해시는 남기지 않는다.
                log.info("QR 체크인 실패 - storeId={}, reason={}", storeId, rejected.reason());
                throw new CustomException(ErrorCode.INVALID_QR_TOKEN);
            }
        };

        CheckIn checkIn = checkInRepository.save(CheckIn.of(childId, store));
        return CheckInResponse.from(checkIn);
    }
}
