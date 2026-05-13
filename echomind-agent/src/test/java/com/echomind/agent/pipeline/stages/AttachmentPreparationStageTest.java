package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.MessageAttachment;
import com.echomind.skill.storage.LocalObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPreparationStageTest {

    @TempDir
    Path tempDir;

    @Test
    void localImageIsConvertedToDataUrlOnlyForModelCall() throws Exception {
        LocalObjectStorageService storage = new LocalObjectStorageService(tempDir);
        Path image = tempDir.resolve("a.png");
        Files.write(image, new byte[] {1, 2, 3, 4});
        var stored = storage.putObject("chat-images/a.png", image, "image/png");

        PipelineContext ctx = new PipelineContext();
        MessageAttachment original = MessageAttachment.image(
            stored.uri(),
            stored.url(),
            "image/png",
            "a.png",
            stored.size()
        );
        ctx.getAttachments().add(original);

        new AttachmentPreparationStage(storage).process(ctx);

        assertThat(ctx.getAttachments()).containsExactly(original);
        assertThat(ctx.getModelAttachments()).hasSize(1);
        assertThat(ctx.getModelAttachments().get(0).url())
            .startsWith("data:image/png;base64,");
    }

    @Test
    void publicImageUrlIsKeptAsIs() {
        LocalObjectStorageService storage = new LocalObjectStorageService(tempDir);
        PipelineContext ctx = new PipelineContext();
        ctx.getAttachments().add(MessageAttachment.image(
            "oss://bucket/chat-images/a.png",
            "https://example.com/a.png",
            "image/png",
            "a.png",
            128L
        ));

        new AttachmentPreparationStage(storage).process(ctx);

        assertThat(ctx.getModelAttachments()).hasSize(1);
        assertThat(ctx.getModelAttachments().get(0).url()).isEqualTo("https://example.com/a.png");
    }
}
