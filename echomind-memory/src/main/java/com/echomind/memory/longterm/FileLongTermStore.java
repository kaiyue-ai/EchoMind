package com.echomind.memory.longterm;

import com.echomind.common.exception.MemoryPersistenceException;
import com.echomind.common.model.AgentMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 基于本地文件系统的长期记忆存储实现。
 *
 * <p>存储格式：
 * <ul>
 *   <li>每个会话对应一个 JSON 文件，文件名格式为 {@code <sanitized-sessionId>.json}。</li>
 *   <li>文件存储在构造时指定的目录下，目录不存在时自动创建。</li>
 *   <li>消息以 Jackson JSON 数组格式序列化，支持所有 Jackson 模块（Java 8 时间、JSR310 等）。</li>
 *   <li>日期序列化格式为 ISO-8601 字符串（而非时间戳），便于人工审查和调试。</li>
 * </ul>
 *
 * <p>并发安全：
 * 同一会话的并发写入不保证原子性（未加文件锁），由上层 {@link com.echomind.memory.MemoryManager}
 * 的会话级串行化写入保证数据一致性。
 *
 * <p>设计决策：
 * <ul>
 *   <li>文件名对 sessionId 进行 sanitize 处理（替换非法字符为下划线），防止路径注入攻击。</li>
 *   <li>load 方法遇到损坏文件时返回空列表并记录警告日志，优于抛出异常，避免单次读取失败
 *       阻塞整个 Agent 流程。</li>
 *   <li>save 方法采用 read-modify-write 模式（先加载已有数据，追加后写回），
 *       保证不丢失历史数据。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see LongTermMemoryStore
 * @since 1.0
 */
public class FileLongTermStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileLongTermStore.class);

    /**
     * 共享的 Jackson ObjectMapper 实例。
     * 注册所有自动发现的模块（JavaTimeModule 等），
     * 并禁用时间戳形式的日期序列化，改用 ISO-8601 字符串格式。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 存储目录路径 —— 所有会话的 JSON 文件都存储在此目录下 */
    private final Path storageDir;

    /**
     * 构造文件长期存储实例。
     *
     * <p>构造时会自动创建存储目录（如不存在），目录创建失败将抛出
     * {@link MemoryPersistenceException}，因为这意味着整个长期记忆系统不可用。
     *
     * @param storageDirPath 存储目录的路径字符串（相对或绝对路径均可）
     * @throws MemoryPersistenceException 当存储目录无法创建时抛出
     */
    public FileLongTermStore(String storageDirPath) {
        this.storageDir = Paths.get(storageDirPath);
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new MemoryPersistenceException("Cannot create storage directory: " + storageDirPath, e);
        }
    }

    /**
     * 保存消息到长期存储（追加模式）。
     *
     * <p>实现策略：
     * <ol>
     *   <li>加载该会话已有的消息（如有）。</li>
     *   <li>将新消息追加到已有消息列表末尾。</li>
     *   <li>完整的合并列表写回 JSON 文件。</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @param messages  要持久化的新消息列表
     * @throws MemoryPersistenceException 当 JSON 序列化或文件写入失败时抛出
     */
    @Override
    public void save(String sessionId, List<AgentMessage> messages) {
        Path file = sessionFile(sessionId);
        try {
            List<AgentMessage> existing = load(sessionId);
            List<AgentMessage> all = new ArrayList<>(existing);
            all.addAll(messages);
            MAPPER.writeValue(file.toFile(), all);
            log.debug("Saved {} messages for session {}", all.size(), sessionId);
        } catch (IOException e) {
            throw new MemoryPersistenceException("Failed to save memory for session: " + sessionId, e);
        }
    }

    /**
     * 加载指定会话的全部长期记忆。
     *
     * <p>容错策略：如果文件不存在、为空或 JSON 解析失败，返回空列表并记录警告日志，
     * 而不是抛出异常。这样可以保证 Agent 流程不会因单个损坏的存储文件而中断。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的全部历史消息列表；无数据时返回空列表
     */
    @Override
    public List<AgentMessage> load(String sessionId) {
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0) return Collections.emptyList();
            AgentMessage[] messages = MAPPER.readValue(data, AgentMessage[].class);
            return Arrays.asList(messages);
        } catch (IOException e) {
            log.warn("Failed to load memory for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按关键词查询长期记忆。
     *
     * <p>使用 Java Stream API 进行内存过滤：对消息内容执行
     * 大小写不敏感的 {@link String#contains(CharSequence)} 匹配。
     *
     * <p>注意：对于大量消息的场景，此实现效率较低（全量加载后过滤）。
     * 生产环境中建议使用 {@code RedisLongTermStore} 或数据库实现的全文索引。
     *
     * @param sessionId 会话唯一标识
     * @param keyword   搜索关键词（大小写不敏感）
     * @return 匹配的消息列表；无匹配时返回空列表
     */
    @Override
    public List<AgentMessage> query(String sessionId, String keyword) {
        List<AgentMessage> all = load(sessionId);
        return all.stream()
            .filter(m -> m.content() != null && m.content().toLowerCase().contains(keyword.toLowerCase()))
            .toList();
    }

    /**
     * 删除指定会话的长期记忆文件。
     *
     * <p>操作是幂等的：如果文件不存在则不执行任何操作，不抛出异常。
     *
     * @param sessionId 要删除的会话唯一标识
     */
    @Override
    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(sessionFile(sessionId));
        } catch (IOException e) {
            log.warn("Failed to delete memory for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 将 sessionId 转换为安全的文件名。
     *
     * <p>安全措施：只保留字母、数字、下划线和连字符，其他字符替换为下划线，
     * 防止路径注入攻击（如 "../etc/passwd" 类型的恶意 sessionId）。
     *
     * @param sessionId 原始会话标识
     * @return sanitize 后的文件名（不含路径，仅文件名）
     */
    private Path sessionFile(String sessionId) {
        String safeName = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return storageDir.resolve(safeName + ".json");
    }
}
