package com.ktc4.backend.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 길이를 알리지 않고 나눠 보낸 본문을 읽다가 상한에서 멈추는지 본다. 길이를 알린 본문은 SignupRateLimitFilterTest 가 본다.
@DisplayName("BodySizeLimitedRequest")
class BodySizeLimitedRequestTest {

    private static MockHttpServletRequest requestWithBody(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/owners/signup");
        request.setContent("a".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    @DisplayName("상한까지는 끝까지 읽는다")
    void readsUpToLimit() throws IOException {
        BodySizeLimitedRequest limited = new BodySizeLimitedRequest(requestWithBody(100), 100);

        assertThat(limited.getInputStream().readAllBytes()).hasSize(100);
    }

    @Test
    @DisplayName("상한을 넘는 순간 읽기를 멈추고 IOException 을 던진다 — 그 뒤는 메모리에 올라가지 않는다")
    void stopsAtLimit() {
        BodySizeLimitedRequest limited = new BodySizeLimitedRequest(requestWithBody(101), 100);

        assertThatThrownBy(() -> limited.getInputStream().readAllBytes()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("한 바이트씩 읽어도 같은 상한이 걸린다")
    void stopsAtLimitByteByByte() throws IOException {
        var in = new BodySizeLimitedRequest(requestWithBody(11), 10).getInputStream();
        for (int i = 0; i < 10; i++) {
            assertThat(in.read()).isNotEqualTo(-1);
        }

        assertThatThrownBy(in::read).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("글자로 읽어도(getReader) 같은 상한이 걸린다")
    void readerIsLimitedToo() {
        BodySizeLimitedRequest limited = new BodySizeLimitedRequest(requestWithBody(101), 100);

        assertThatThrownBy(() -> limited.getReader().lines().count()).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("보낸 쪽이 적은 인코딩 이름이 틀리면 UTF-8 로 읽는다 — 500 이 나지 않게")
    void fallsBackToUtf8ForUnknownEncoding() {
        assertThat(BodySizeLimitedRequest.charsetOf("not-a-charset")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(BodySizeLimitedRequest.charsetOf("%%%")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(BodySizeLimitedRequest.charsetOf(null)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(BodySizeLimitedRequest.charsetOf("ISO-8859-1")).isEqualTo(StandardCharsets.ISO_8859_1);
    }
}
