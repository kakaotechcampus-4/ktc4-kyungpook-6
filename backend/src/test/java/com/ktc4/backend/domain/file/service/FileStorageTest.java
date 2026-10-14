package com.ktc4.backend.domain.file.service;

import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.ktc4.backend.support.SignupMultipart.file;
import static com.ktc4.backend.support.SignupMultipart.jpeg;
import static com.ktc4.backend.support.SignupMultipart.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 디스크(테스트마다 새로 만드는 임시 폴더)에 쓰고 읽는다.
@DisplayName("FileStorage")
class FileStorageTest {

    @TempDir
    Path tempDir;

    private Path uploadDir;
    private FileStorage storage;

    @BeforeEach
    void setUp() {
        // 아직 없는 폴더를 가리킨다 — 처음 저장할 때 만들어지는지 함께 본다
        uploadDir = tempDir.resolve("uploads");
        storage = new FileStorage(uploadDir.toString());
    }

    private static long countFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("저장하면 내용이 그대로 디스크에 있고, 저장한 이름으로 다시 꺼낼 수 있다")
    void savesAndLoads() throws IOException {
        MockMultipartFile upload = jpeg("storeImage");

        String storedName = storage.save(upload, FileType.JPEG);

        assertThat(Files.readAllBytes(uploadDir.resolve(storedName))).isEqualTo(upload.getBytes());
        Resource loaded = storage.load(storedName);
        assertThat(loaded.getContentAsByteArray()).isEqualTo(upload.getBytes());
    }

    @Test
    @DisplayName("저장 폴더가 없으면 처음 저장할 때 만든다 — 그 전에는 만들지 않는다")
    void createsDirectoryOnFirstSave() {
        assertThat(uploadDir).doesNotExist();

        storage.save(jpeg("storeImage"), FileType.JPEG);

        assertThat(uploadDir).isDirectory();
    }

    @Test
    @DisplayName("저장하는 이름은 서버가 만든 무작위 값에 가린 형식의 확장자를 붙인 것이다 — 보낸 파일 이름은 쓰지 않는다")
    void storedNameIgnoresOriginalFilename() throws IOException {
        // 저장 폴더 밖을 가리키는 이름, 확장자를 속인 이름
        MockMultipartFile traversal = file("bizCert", "../../outside.pdf", "application/pdf", pdf("bizCert").getBytes());
        MockMultipartFile disguised = file("storeImage", "photo.html", "text/html", jpeg("storeImage").getBytes());

        String first = storage.save(traversal, FileType.PDF);
        String second = storage.save(disguised, FileType.JPEG);

        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(second).matches("[0-9a-f-]{36}\\.jpg");
        assertThat(first).doesNotContain("outside");
        assertThat(second).doesNotContain("photo").doesNotContain("html");
        // 두 파일 모두 저장 폴더 바로 아래에만 있다
        assertThat(countFiles(uploadDir)).isEqualTo(2);
        assertThat(countFiles(tempDir)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 파일을 두 번 저장해도 이름이 달라 서로 덮어쓰지 않는다")
    void doesNotOverwrite() throws IOException {
        String first = storage.save(jpeg("storeImage"), FileType.JPEG);
        String second = storage.save(jpeg("storeImage"), FileType.JPEG);

        assertThat(first).isNotEqualTo(second);
        assertThat(countFiles(uploadDir)).isEqualTo(2);
    }

    @Test
    @DisplayName("디스크에 없는 파일을 꺼내면 FILE_NOT_FOUND")
    void loadMissingFile() {
        assertThatThrownBy(() -> storage.load("00000000-0000-0000-0000-000000000000.jpg"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND));
    }

    @Test
    @DisplayName("지우면 디스크에서 사라지고, 없는 파일을 지워도 예외가 나지 않는다")
    void deletes() {
        String storedName = storage.save(jpeg("storeImage"), FileType.JPEG);

        storage.delete(storedName);

        assertThat(uploadDir.resolve(storedName)).doesNotExist();
        assertThatCode(() -> storage.delete(storedName)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"../secret.txt", "../../etc/passwd", "sub/file.jpg", "..", "."})
    @DisplayName("저장 폴더 바로 아래가 아닌 이름으로는 꺼내지 않는다")
    void rejectsPathsOutsideUploadDir(String name) throws IOException {
        // 저장 폴더 옆에 실제로 파일을 둔다 — 경로가 풀리면 읽히는 자리다
        Files.createDirectories(uploadDir);
        Files.writeString(tempDir.resolve("secret.txt"), "outside");

        assertThatThrownBy(() -> storage.load(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장 폴더 밖을 가리키는 이름으로 지워도 바깥 파일은 그대로다")
    void deleteDoesNotTouchOutside() throws IOException {
        Files.createDirectories(uploadDir);
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "outside");

        assertThatCode(() -> storage.delete("../secret.txt")).doesNotThrowAnyException();

        assertThat(outside).exists();
    }
}
