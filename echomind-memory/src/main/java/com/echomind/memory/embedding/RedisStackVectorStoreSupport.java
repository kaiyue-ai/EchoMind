package com.echomind.memory.embedding;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.StatusOutput;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Redis Stack 向量存储公共工具方法。
 *
 * <p>被 {@code UserMemoryVectorStore}、{@code AgentKnowledgeService} 共用，
 * 避免每个类重复实现 Redis 命令执行、
 * TAG 转义、向量字节转换等基础操作。</p>
 */
public final class RedisStackVectorStoreSupport {

    private RedisStackVectorStoreSupport() {
    }

    // ── Redis 命令执行 ──

    public static Object executeArrayCommand(RedisConnection connection, String command, byte[]... args) {
        return executeCommand(connection, command, new ArrayOutput<>(ByteArrayCodec.INSTANCE), args);
    }

    public static Object executeStatusCommand(RedisConnection connection, String command, byte[]... args) {
        return executeCommand(connection, command, new StatusOutput<>(ByteArrayCodec.INSTANCE), args);
    }

    public static Object executeCommand(RedisConnection connection, String command,
                                         CommandOutput<byte[], byte[], ?> output, byte[]... args) {
        if (connection instanceof LettuceConnection lettuceConnection) {
            return lettuceConnection.execute(command, output, args);
        }
        return connection.execute(command, args);
    }

    // ── 类型转换 ──

    public static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    public static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? null : String.valueOf(value);
    }

    public static List<?> asList(Object raw) {
        if (raw instanceof List<?> list) {
            return list;
        }
        if (raw instanceof Object[] array) {
            return Arrays.asList(array);
        }
        return List.of();
    }

    /** 从 key-value 对列表中查找指定 key 对应的 value。 */
    public static List<?> findValue(List<?> fields, String name) {
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            if (name.equals(text(fields.get(i)))) {
                return asList(fields.get(i + 1));
            }
        }
        return List.of();
    }

    public static double parseDouble(Object value) {
        try {
            return Double.parseDouble(text(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析 Long，失败返回 null。 */
    public static Long parseLong(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (Exception e) {
            return null;
        }
    }

    // ── 向量编码 ──

    /** 将 double[] 向量编码为 FLOAT32 little-endian 字节数组。 */
    public static byte[] vectorBytes(double[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : vector) {
            buffer.putFloat((float) value);
        }
        return buffer.array();
    }

    // ── 字符串处理 ──

    /** Redis Stack TAG 字段值转义。 */
    public static String escapeTag(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('\\').append(c);
            }
        }
        return sb.toString();
    }

    /** Base64 URL-safe 编码会话/Agent ID，用作 Redis key 前缀片段。 */
    public static String encodeId(String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    /** COSINE 距离转相似度 (0~1)，处理 NaN/Inf 边界。 */
    public static double distanceToSimilarity(double distance) {
        if (Double.isNaN(distance) || Double.isInfinite(distance)) {
            return 0;
        }
        return clamp(1 - distance, 0, 1);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 递归提取异常链中所有 message，用于判断 "index already exists" 等场景。 */
    public static String exceptionMessage(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                message.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return message.toString().toLowerCase();
    }

    public static boolean isIndexAlreadyExists(Exception e) {
        return exceptionMessage(e).contains("already exists");
    }
}
