package com.echomind.skill.marketplace;

import com.echomind.skill.loader.SkillJarLoader;
import com.echomind.skill.registry.SkillRegistry;
import com.echomind.skill.storage.LocalObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deleteByDatabaseIdUnregistersRuntimeSkillIdAndRemovesStoredJar() throws Exception {
        SkillEntityRepository repository = mock(SkillEntityRepository.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        MarketplaceService service = new MarketplaceService(
            repository,
            mock(SkillJarLoader.class),
            registry,
            tempDir.resolve("marketplace").toString(),
            new LocalObjectStorageService(tempDir.resolve("objects"))
        );

        Path jar = Files.createTempFile(tempDir, "skill-", ".jar");
        SkillRepository entity = new SkillRepository();
        entity.setId("db-id");
        entity.setName("upload-probe");
        entity.setVersion("1.0.0");
        entity.setJarPath("local://skills/upload-probe/1.0.0/upload-probe-1.0.0.jar");
        Files.createDirectories(tempDir.resolve("objects/skills/upload-probe/1.0.0"));
        Files.copy(jar, tempDir.resolve("objects/skills/upload-probe/1.0.0/upload-probe-1.0.0.jar"));

        when(repository.findById("db-id")).thenReturn(Optional.of(entity));

        Optional<String> deletedSkillId = service.delete("db-id");

        assertThat(deletedSkillId).contains("upload-probe@1.0.0");
        assertThat(Files.exists(tempDir.resolve("objects/skills/upload-probe/1.0.0/upload-probe-1.0.0.jar")))
            .isFalse();
        verify(registry).unregister("upload-probe@1.0.0");
        verify(repository).deleteById("db-id");
    }

    @Test
    void deleteBySkillIdFindsRepositoryRecordByNameAndVersion() throws Exception {
        SkillEntityRepository repository = mock(SkillEntityRepository.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        MarketplaceService service = new MarketplaceService(
            repository,
            mock(SkillJarLoader.class),
            registry,
            tempDir.resolve("marketplace").toString(),
            new LocalObjectStorageService(tempDir.resolve("objects"))
        );

        Path jar = Files.createTempFile(tempDir, "skill-", ".jar");
        SkillRepository entity = new SkillRepository();
        entity.setId("db-id");
        entity.setName("upload-probe");
        entity.setVersion("1.0.0");
        entity.setJarPath(jar.toString());

        when(repository.findById("upload-probe@1.0.0")).thenReturn(Optional.empty());
        when(repository.findByNameAndVersion("upload-probe", "1.0.0")).thenReturn(Optional.of(entity));

        Optional<String> deletedSkillId = service.delete("upload-probe@1.0.0");

        assertThat(deletedSkillId).contains("upload-probe@1.0.0");
        verify(registry).unregister("upload-probe@1.0.0");
        verify(repository).deleteById("db-id");
    }
}
