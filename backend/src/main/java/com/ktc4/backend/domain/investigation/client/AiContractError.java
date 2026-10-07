package com.ktc4.backend.domain.investigation.client;

/** AI 호출 실패: 응답이 약속과 다르다(4xx·해석 불가·결과 수·storeId·모르는 값). 다시 보내도 같아서 재시도하지 않는다. */
public class AiContractError extends AiException {

    public AiContractError(String message) {
        super(message);
    }

    public AiContractError(String message, Throwable cause) {
        super(message, cause);
    }
}
