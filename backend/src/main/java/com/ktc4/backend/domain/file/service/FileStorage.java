package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 올라온 파일을 서버 디스크에 두고 꺼낸다.
 *
 * <p>저장하는 이름은 서버가 만든 무작위 값이다. 보낸 쪽이 붙인 파일 이름은 쓰지 않는다 —
 * {@code ../} 같은 경로 문자로 저장 폴더 밖에 쓰게 하거나 다른 파일을 덮어쓰게 할 수 있기 때문이다.
 * 이름을 추측할 수 없어야 하는 이유도 있다. 파일을 내려주는 API 는 기록 번호로 찾지만, 이름이 순서대로면
 * 저장 폴더가 실수로 웹에 열렸을 때 전부 긁어 갈 수 있다.
 *
 * <p>저장 폴더는 설정값({@code upload.dir})이다. 배포 환경에서는 컨테이너를 새로 만들어도 남도록 볼륨에 둔다.
 */
@Slf4j
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${upload.dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 파일을 저장하고 저장한 이름을 돌려준다.
     *
     * @param file 올라온 파일
     * @param type 서버가 가린 형식. 확장자를 이 값으로 붙인다
     * @return 저장한 이름. {@link #load}·{@link #delete} 에 넘긴다
     * @throws UncheckedIOException 디스크에 쓰지 못하면
     */
    public String save(MultipartFile file, FileType type) {
        String storedName = UUID.randomUUID() + "." + type.extension();
        try {
            // 폴더는 처음 쓸 때 만든다. 기동할 때 만들면 파일을 쓰지 않는 테스트·환경에도 빈 폴더가 생긴다
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, resolve(storedName));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("파일을 저장하지 못했습니다", e);
        }
        return storedName;
    }

    /**
     * 저장한 파일을 꺼낸다.
     *
     * @param storedName {@link #save} 가 돌려준 이름
     * @return 파일 내용
     * @throws CustomException 기록은 있는데 디스크에 파일이 없으면 {@code FILE_NOT_FOUND}
     */
    public Resource load(String storedName) {
        Path path = resolve(storedName);
        if (!Files.isRegularFile(path)) {
            // 기록과 디스크가 어긋난 상태다 — 볼륨이 빠졌거나 누가 파일을 지웠다
            log.warn("파일 기록은 있는데 디스크에 없습니다 - storedName={}", storedName);
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }
        return new FileSystemResource(path);
    }

    /**
     * 저장한 파일을 지운다. 가입이 실패해 기록이 남지 않을 때 디스크에 먼저 쓴 파일을 치우는 데 쓴다.
     *
     * <p>지우지 못해도 예외를 던지지 않는다 — 치우는 중의 실패가 원래 실패의 원인을 가리면 안 된다.
     *
     * @param storedName {@link #save} 가 돌려준 이름
     */
    public void delete(String storedName) {
        try {
            Files.deleteIfExists(resolve(storedName));
        } catch (IOException | RuntimeException e) {
            log.warn("저장한 파일을 지우지 못했습니다 - storedName={}, 원인={}", storedName, e.getClass().getSimpleName());
        }
    }

    // 이름은 서버가 만든 값이라 경로 문자가 들어올 일이 없다. 그래도 저장 폴더 바로 아래가 아니면 쓰지 않는다 —
    // 나중에 다른 곳에서 이 클래스를 부르며 바깥 값을 넘겨도 폴더 밖을 건드리지 못하게.
    private Path resolve(String storedName) {
        Path path = root.resolve(storedName).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("저장 폴더 밖의 경로입니다");
        }
        return path;
    }
}
