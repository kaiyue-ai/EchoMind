package com.echomind.console.service;

import com.echomind.skill.storage.LocalObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageApplicationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadChatImageStoresAttachmentReference() {
        StorageApplicationService service = new StorageApplicationService(
            new LocalObjectStorageService(tempDir)
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.png",
            "image/png",
            new byte[] {1, 2, 3}
        );

        var response = service.uploadChatImage(file);

        assertThat(response.mode()).isEqualTo("local");
        assertThat(response.attachment().type()).isEqualTo("image");
        assertThat(response.attachment().uri()).startsWith("local://chat-images/");
        assertThat(response.attachment().url()).startsWith("/api/storage/objects/chat-images/");
        assertThat(Files.exists(tempDir.resolve(response.attachment().uri().substring("local://".length()))))
            .isTrue();
    }

    @Test
    void nonImageUploadIsRejected() {
        StorageApplicationService service = new StorageApplicationService(
            new LocalObjectStorageService(tempDir)
        );
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "demo.txt",
            "text/plain",
            new byte[] {1}
        );

        assertThatThrownBy(() -> service.uploadChatImage(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("仅支持");
    }
}
