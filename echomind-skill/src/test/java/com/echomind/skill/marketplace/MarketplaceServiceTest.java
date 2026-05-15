package com.echomind.skill.marketplace;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.echomind.skill.loader.SkillClassLoader;
import com.echomind.skill.loader.SkillJarLoader;
import com.echomind.skill.registry.SkillRegistry;
import com.echomind.skill.storage.LocalObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void restoreFromDatabaseHydratesMissingMarketplaceCacheFromStoredJarAndKeepsDisabledState() throws Exception {
        SkillEntityRepository repository = mock(SkillEntityRepository.class);
        SkillJarLoader loader = mock(SkillJarLoader.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        Path objectsDir = tempDir.resolve("objects");
        MarketplaceService service = new MarketplaceService(
            repository,
            loader,
            registry,
            tempDir.resolve("marketplace").toString(),
            new LocalObjectStorageService(objectsDir)
        );

        Path storedJar = objectsDir.resolve("skills/upload-probe/1.0.0/upload-probe-1.0.0.jar");
        Files.createDirectories(storedJar.getParent());
        Files.writeString(storedJar, "jar-bytes");
        SkillRepository entity = new SkillRepository();
        entity.setName("upload-probe");
        entity.setVersion("1.0.0");
        entity.setState(SkillState.DISABLED);
        entity.setJarPath("local://skills/upload-probe/1.0.0/upload-probe-1.0.0.jar");

        Skill skill = new TestSkill("upload-probe", "1.0.0");
        SkillClassLoader classLoader = mock(SkillClassLoader.class);
        Path cachedJar = tempDir.resolve("marketplace/upload-probe-1.0.0.jar");
        when(repository.findAll()).thenReturn(List.of(entity));
        when(registry.listAll()).thenReturn(List.of());
        when(loader.load(eq(cachedJar))).thenReturn(new SkillJarLoader.SkillLoadResult(skill, classLoader, cachedJar));

        service.restoreFromDatabase();

        assertThat(Files.readString(cachedJar)).isEqualTo("jar-bytes");
        verify(registry).register(same(skill), same(classLoader));
        verify(registry).disable("upload-probe@1.0.0");
    }

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

    private record TestSkill(String name, String version) implements Skill {
        @Override
        public SkillMetadata metadata() {
            return new SkillMetadata(
                name,
                version,
                "Test skill",
                Map.of(),
                List.of(),
                "EchoMind",
                List.of("test")
            );
        }

        @Override
        public CompletableFuture<SkillResult> execute(SkillRequest request) {
            return CompletableFuture.completedFuture(SkillResult.success("{}", 0));
        }
    }
}
