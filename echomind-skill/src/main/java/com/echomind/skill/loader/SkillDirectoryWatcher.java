package com.echomind.skill.loader;

import com.echomind.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Skill目录热加载监视器，监控指定目录下JAR文件的增删改事件。
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li><b>启动扫描</b>：启动时扫描监控目录中已存在的{@code .jar}文件并自动加载</li>
 *   <li><b>实时监控</b>：通过Java NIO {@link WatchService}监控目录变更事件</li>
 *   <li><b>自动加载</b>：检测到新的或修改的JAR文件时，自动调用{@link SkillJarLoader}加载并注册到{@link SkillRegistry}</li>
 *   <li><b>防重复</b>：通过{@code loadedFiles}集合跟踪已加载文件，避免重复加载</li>
 * </ul>
 *
 * <p>支持的事件类型：</p>
 * <ul>
 *   <li>{@code ENTRY_CREATE} — 新JAR文件放入目录</li>
 *   <li>{@code ENTRY_MODIFY} — JAR文件被更新（如覆盖部署）</li>
 *   <li>{@code ENTRY_DELETE} — JAR文件被删除</li>
 * </ul>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>监视循环在独立的守护线程中运行（线程名：{@code skill-dir-watcher}）</li>
 *   <li>使用{@code volatile boolean running}控制线程生命周期</li>
 *   <li>加载JAR时添加500ms延迟，避免文件复制未完成时触发加载</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillJarLoader
 * @see SkillRegistry
 */
public class SkillDirectoryWatcher {

    /** SLF4J日志记录器，用于记录文件监控和加载事件 */
    private static final Logger log = LoggerFactory.getLogger(SkillDirectoryWatcher.class);

    /** 被监控的目录路径，JAR文件的创建、修改和删除均在此目录下被监听 */
    private final Path watchDir;

    /** JAR文件加载器，负责从JAR中提取Skill实例 */
    private final SkillJarLoader jarLoader;

    /** Skill注册中心，加载成功的Skill将注册到此 */
    private final SkillRegistry registry;

    /**
     * 已加载文件名的集合，用于防止重复加载。
     * 使用{@link ConcurrentHashMap#newKeySet()}创建线程安全的Set。
     */
    private final Set<String> loadedFiles = ConcurrentHashMap.newKeySet();

    /** 监视器运行状态标志，使用volatile确保线程间可见 */
    private volatile boolean running;

    /** 监视线程引用，用于在停止时中断 */
    private Thread watcherThread;

    /**
     * 构造目录监视器。
     *
     * @param watchDir  要监控的目录路径
     * @param jarLoader JAR文件加载器
     * @param registry  Skill注册中心
     */
    public SkillDirectoryWatcher(Path watchDir, SkillJarLoader jarLoader, SkillRegistry registry) {
        this.watchDir = watchDir;
        this.jarLoader = jarLoader;
        this.registry = registry;
    }

    /**
     * 启动目录监视器。
     *
     * <p>启动流程：</p>
     * <ol>
     *   <li>设置运行标志为true</li>
     *   <li>创建并启动守护线程执行监视循环</li>
     *   <li>确保监控目录存在（必要时创建）</li>
     *   <li>扫描目录中已有的JAR文件并加载</li>
     * </ol>
     */
    public void start() {
        running = true;
        watcherThread = new Thread(this::watchLoop, "skill-dir-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();

        // 加载目录中已有的JAR文件
        try {
            Files.createDirectories(watchDir);
            try (var stream = Files.list(watchDir)) {
                stream.filter(f -> f.toString().endsWith(".jar"))
                    .forEach(this::loadJar);
            }
        } catch (IOException e) {
            log.error("Failed to scan skill directory", e);
        }

        log.info("Skill directory watcher started: {}", watchDir);
    }

    /**
     * 停止目录监视器。
     *
     * <p>设置运行标志为false并中断监视线程。线程将在下一次轮询或阻塞时退出。</p>
     */
    public void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    /**
     * 监视循环主方法，在独立线程中运行。
     *
     * <p>使用{@link WatchService}轮询文件系统事件（2秒超时），
     * 对JAR文件的创建/修改事件执行加载，对删除事件执行卸载标记。</p>
     */
    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            watchDir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (ClosedWatchServiceException e) {
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    Path fileName = watchDir.resolve((Path) event.context());
                    if (!fileName.toString().endsWith(".jar")) continue;

                    if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                        loadJar(fileName);
                    } else if (kind == ENTRY_DELETE) {
                        unloadJar(fileName);
                    }
                }
                key.reset();
            }
        } catch (IOException e) {
            log.error("Watch loop error", e);
        }
    }

    /**
     * 加载指定的JAR文件。
     *
     * <p>加载前先检查是否已加载（防重复），然后等待500ms确保文件写入完成，
     * 再调用{@link SkillJarLoader#load(Path)}进行实际加载。</p>
     *
     * @param jarPath JAR文件的完整路径
     */
    private void loadJar(Path jarPath) {
        String key = jarPath.getFileName().toString();
        if (loadedFiles.contains(key)) return;

        try {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            SkillJarLoader.SkillLoadResult result = jarLoader.load(jarPath);
            registry.register(result.skill(), result.classLoader());
            loadedFiles.add(key);
            log.info("Auto-loaded skill from: {}", key);
        } catch (Exception e) {
            log.error("Failed to auto-load skill: {}", key, e);
        }
    }

    /**
     * 标记JAR文件为已卸载。
     *
     * <p>从已加载文件集合中移除文件名。实际的Skill注销由其他机制处理。</p>
     *
     * @param jarPath JAR文件的完整路径
     */
    private void unloadJar(Path jarPath) {
        String key = jarPath.getFileName().toString();
        loadedFiles.remove(key);
    }
}
