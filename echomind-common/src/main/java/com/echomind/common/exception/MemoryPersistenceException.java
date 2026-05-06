package com.echomind.common.exception;

/**
 * <h2>记忆持久化异常</h2>
 * 当短期记忆（对话窗口）向长期存储写入或从长期存储读取时发生失败时抛出。
 *
 * <h3>触发场景</h3>
 * <ul>
 *   <li>文件系统存储：磁盘空间不足、文件权限不足、JSON 序列化错误</li>
 *   <li>Redis 存储：连接超时、集群不可用、键冲突、反序列化失败的脏数据</li>
 *   <li>数据完整性：存储文件损坏、消息格式与当前版本不兼容</li>
 * </ul>
 *
 * <h3>设计考量</h3>
 * 记忆持久化失败不应阻断对话流程——当前会话的短期记忆窗口中的消息依然可用。
 * 本异常用于向上层报告持久化操作的状态，由调用方决定是重试、降级还是告警。
 *
 * @see com.echomind.memory.LongTermMemoryStore 长期记忆存储 SPI
 * @see com.echomind.memory.FileLongTermStore 基于文件系统的实现
 * @see com.echomind.memory.RedisLongTermStore 基于 Redis 的实现
 */
public class MemoryPersistenceException extends EchoMindException {
    /**
     * 使用错误描述和原始异常构造记忆持久化异常。
     * 必须提供原始异常以保留完整的故障链路信息（例如 I/O 异常或 Redis 命令异常）。
     *
     * @param message 人类可读的错误描述，说明持久化操作的上下文（如 sessionId、文件路径等）
     * @param cause   底层存储系统抛出的原始异常
     */
    public MemoryPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
