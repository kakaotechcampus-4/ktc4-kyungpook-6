package com.ktc4.backend.global.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 본문을 정한 바이트 수까지만 읽게 하는 요청. 넘으면 읽는 쪽에 {@link IOException} 을 던진다.
 *
 * <p>본문 길이를 미리 알리지 않고(Content-Length 없이) 나눠 보내는 요청에 쓴다. 길이를 알린 요청은
 * {@link SignupRateLimitFilter} 가 읽기 전에 거절하지만, 알리지 않은 요청은 읽어 봐야 크기를 안다.
 * 상한을 넘는 순간 멈추므로 메모리에 올라가는 양은 상한을 넘지 않는다.
 *
 * <p>JSON 을 읽던 중에 던져지므로 응답은 "읽을 수 없는 본문"(400 {@code invalid-request})이 된다.
 */
class BodySizeLimitedRequest extends HttpServletRequestWrapper {

    private final long maxBytes;
    private ServletInputStream limited;

    BodySizeLimitedRequest(HttpServletRequest request, long maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (limited == null) {
            limited = new LimitedInputStream(super.getInputStream(), maxBytes);
        }
        return limited;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), charsetOf(getCharacterEncoding())));
    }

    // 보낸 쪽이 적은 인코딩 이름이 틀리면 UTF-8 로 읽는다 — 그대로 두면 인코딩을 찾다가 500 이 난다
    static Charset charsetOf(String encoding) {
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        private LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new IOException("요청 본문이 상한(" + maxBytes + "바이트)을 넘었습니다");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
