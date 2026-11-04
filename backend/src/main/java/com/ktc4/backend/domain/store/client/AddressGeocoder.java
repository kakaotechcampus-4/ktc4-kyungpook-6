package com.ktc4.backend.domain.store.client;

/**
 * 도로명 주소를 위경도로 바꾼다. 지금 구현은 카카오 로컬 주소 검색({@link KakaoAddressGeocoder})이다.
 */
public interface AddressGeocoder {

    /**
     * 쓸 수 있는지 — 키가 없으면 false. 이때 좌표 채우기는 아무것도 하지 않는다(앱은 그대로 뜬다).
     */
    boolean isEnabled();

    /**
     * 주소의 좌표를 찾는다.
     *
     * @param address 도로명 주소
     * @return 찾음·못 찾음·묻지 못함
     */
    GeocodeResult geocode(String address);
}
