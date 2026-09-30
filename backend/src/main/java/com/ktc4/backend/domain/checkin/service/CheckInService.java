package com.ktc4.backend.domain.checkin.service;

import com.ktc4.backend.domain.checkin.dto.CheckInResponse;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.checkin.repository.CheckInRepository;
import com.ktc4.backend.domain.qr.service.QrCredentialService;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * <p>⚠️ 지금은 아무 {@code storeId} 로나 부를 수 있다. 점주 로그인이 생기면 로그인한 점주의
     * 가게인지 컨트롤러에서 확인해야 한다.
     *
     * @param storeId   체크인할 가게 ID
     * @param qrPayload 점주 앱이 스캔한 QR 문자열
     * @return 체크인 기록 ID 와 시각. 아동 정보는 담지 않는다
     * @throws CustomException 가게가 없으면 {@code STORE_NOT_FOUND}, QR 이 맞지 않으면 {@code INVALID_QR_TOKEN}
     */
    @Transactional
    public CheckInResponse checkIn(Long storeId, String qrPayload) {
        Store store = storeService.findStore(storeId);
        Long childId = qrCredentialService.resolveChildId(qrPayload)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_QR_TOKEN));

        CheckIn checkIn = checkInRepository.save(CheckIn.of(childId, store));
        return CheckInResponse.from(checkIn);
    }
}
