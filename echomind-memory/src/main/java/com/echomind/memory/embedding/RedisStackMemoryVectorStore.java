package com.echomind.memory.embedding;

import com.echomind.memory.redis.RedisKeyScanner;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.StatusOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Redis Stack 向量检索实现。
 *
 * <p>Redis Stack 负责 KNN 检索。普通聊天向量不再写 MySQL，也不做 MySQL 线性兜底。</p>
 */
@Slf4j
public class RedisStackMemoryVectorStore implements MemoryVectorStore {

    private static final byte[] FIELD_SESSION_ID = bytes("session_id");
    private static final byte[] FIELD_MESSAGE_ID = bytes("message_id");
    private static final byte[] FIELD_ROLE = bytes("role");
    private static final byte[] FIELD_CONTENT_PREVIEW = bytes("content_preview");
    private static final byte[] FIELD_EMBEDDING = bytes("embedding");

    private final RedisConnectionFactory connectionFactory;
    private final RedisKeyScanner keyScanner;
    private final String indexName;
    private final String keyPrefix;

    private volatile boolean indexReady;
    private volatile int indexDimension;

    public RedisStackMemoryVectorStore(RedisConnectionFactory connectionFactory,
                                       String indexName,
                                       String keyPrefix) {
        this.connectionFactory = connectionFactory;
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(redis);
        this.indexName = blankToDefault(indexName, "idx:echomind:memory:vectors");
        this.keyPrefix = blankToDefault(keyPrefix, "echomind:memory:vector:");
    }

    @Override
    public void save(MemoryVectorRecord record) {
        if (record == null || record.embedding() == null || record.embedding().length == 0) {
            return;
        }
        try {
            ensureIndex(record.embedding().length);
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.execute("HSET",
                    bytes(key(record.sessionId(), record.messageId())),
                    FIELD_SESSION_ID, bytes(record.sessionId()),
                    FIELD_MESSAGE_ID, bytes(String.valueOf(record.messageId())),
                    FIELD_ROLE, bytes(record.role()),
                    FIELD_CONTENT_PREVIEW, bytes(record.contentPreview()),
                    FIELD_EMBEDDING, vectorBytes(record.embedding())
                );
            }
        } catch (Exception e) {
            log.warn("Redis Stack memory vector save failed session={} messageId={}: {}",
                record.sessionId(), record.messageId(), e.getMessage());
        }
    }

    @Override
    public List<MemorySearchHit> search(String sessionId, double[] queryVector, int topK) {
        if (sessionId == null || sessionId.isBlank() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        try {
            ensureIndex(queryVector.length);
            List<MemorySearchHit> hits = searchRedis(sessionId, queryVector, topK);
            return hits;
        } catch (Exception e) {
            log.warn("Redis Stack memory vector search failed session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            keyScanner.deleteByPattern(keyPrefix + encodedSessionId(sessionId) + ":*");
        } catch (Exception e) {
            log.warn("Failed to delete Redis Stack memory vectors for session {}: {}", sessionId, e.getMessage());
        }
    }

    private synchronized void ensureIndex(int dimension) {
        if (indexReady && indexDimension == dimension) {
            return;
        }
        if (indexExists()) {
            indexReady = true;
            indexDimension = dimension;
            return;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            executeStatusCommand(connection, "FT.CREATE",
                bytes(indexName),
                bytes("ON"), bytes("HASH"),
                bytes("PREFIX"), bytes("1"), bytes(keyPrefix),
                bytes("SCHEMA"),
                bytes("session_id"), bytes("TAG"),
                bytes("message_id"), bytes("NUMERIC"),
                bytes("role"), bytes("TAG"),
                bytes("content_preview"), bytes("TEXT"),
                bytes("embedding"), bytes("VECTOR"), bytes("HNSW"), bytes("6"),
                bytes("TYPE"), bytes("FLOAT32"),
                bytes("DIM"), bytes(String.valueOf(dimension)),
                bytes("DISTANCE_METRIC"), bytes("COSINE")
            );
            indexReady = true;
            indexDimension = dimension;
            log.info("Created Redis Stack memory vector index {} with dimension {}", indexName, dimension);
        } catch (Exception e) {
            if (isIndexAlreadyExists(e)) {
                indexReady = true;
                indexDimension = dimension;
                return;
            }
            indexReady = false;
            throw e;
        }
    }

    private List<MemorySearchHit> searchRedis(String sessionId, double[] queryVector, int topK) {
        String query = "(@session_id:{" + escapeTag(sessionId) + "})=>[KNN "
            + topK + " @embedding $vec AS score]";
        Object raw;
        try (RedisConnection connection = connectionFactory.getConnection()) {
            raw = executeArrayCommand(connection, "FT.SEARCH",
                bytes(indexName),
                bytes(query),
                bytes("PARAMS"), bytes("2"), bytes("vec"), vectorBytes(queryVector),
                bytes("SORTBY"), bytes("score"), bytes("ASC"),
                bytes("RETURN"), bytes("4"), bytes("message_id"), bytes("role"), bytes("content_preview"), bytes("score"),
                bytes("DIALECT"), bytes("2")
            );
        }
        return parseSearchResult(raw);
    }

    private boolean indexExists() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Object raw = executeArrayCommand(connection, "FT._LIST");
            List<?> indexes = asList(raw);
            if (indexes.isEmpty()) {
                return false;
            }
            for (Object index : indexes) {
                if (indexName.equals(text(index))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<MemorySearchHit> parseSearchResult(Object raw) {
        List<?> rows = asList(raw);
        if (rows.size() <= 1) {
            return List.of();
        }
        List<?> structuredResults = findValue(rows, "results");
        if (!structuredResults.isEmpty()) {
            return parseStructuredResults(structuredResults);
        }
        List<MemorySearchHit> hits = new ArrayList<>();
        for (int i = 1; i + 1 < rows.size(); i += 2) {
            Object fieldsRaw = rows.get(i + 1);
            List<?> fields = asList(fieldsRaw);
            if (fields.isEmpty()) {
                continue;
            }
            MemorySearchHit hit = parseFields(fields);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private List<MemorySearchHit> parseStructuredResults(List<?> results) {
        List<MemorySearchHit> hits = new ArrayList<>();
        for (Object result : results) {
            List<?> resultFields = asList(result);
            if (resultFields.isEmpty()) {
                continue;
            }
            MemorySearchHit hit = parseFields(findValue(resultFields, "extra_attributes"));
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private MemorySearchHit parseFields(List<?> fields) {
        Long messageId = null;
        String role = null;
        String content = null;
        double score = 0;
        for (int j = 0; j + 1 < fields.size(); j += 2) {
            String name = text(fields.get(j));
            Object value = fields.get(j + 1);
            if ("message_id".equals(name)) {
                messageId = parseLong(value);
            } else if ("role".equals(name)) {
                role = text(value);
            } else if ("content_preview".equals(name)) {
                content = text(value);
            } else if ("score".equals(name)) {
                score = distanceToSimilarity(parseDouble(value));
            }
        }
        return messageId != null && content != null
            ? new MemorySearchHit(messageId, role, content, score)
            : null;
    }

    private byte[] vectorBytes(double[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : vector) {
            buffer.putFloat((float) value);
        }
        return buffer.array();
    }

    private String key(String sessionId, Long messageId) {
        return keyPrefix + encodedSessionId(sessionId) + ":" + messageId;
    }

    private String encodedSessionId(String sessionId) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    }

    private String escapeTag(String value) {
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

    private boolean isIndexAlreadyExists(Exception e) {
        String message = exceptionMessage(e);
        return message.contains("index already exists") || message.contains("already exists");
    }

    private Object executeArrayCommand(RedisConnection connection, String command, byte[]... args) {
        return executeCommand(connection, command, new ArrayOutput<>(ByteArrayCodec.INSTANCE), args);
    }

    private Object executeStatusCommand(RedisConnection connection, String command, byte[]... args) {
        return executeCommand(connection, command, new StatusOutput<>(ByteArrayCodec.INSTANCE), args);
    }

    private Object executeCommand(RedisConnection connection, String command,
                                  CommandOutput<byte[], byte[], ?> output, byte[]... args) {
        if (connection instanceof LettuceConnection lettuceConnection) {
            return lettuceConnection.execute(command, output, args);
        }
        return connection.execute(command, args);
    }

    private String exceptionMessage(Throwable throwable) {
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

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? null : String.valueOf(value);
    }

    private Long parseLong(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (Exception e) {
            return null;
        }
    }

    private double parseDouble(Object value) {
        try {
            return Double.parseDouble(text(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private List<?> asList(Object raw) {
        if (raw instanceof List<?> list) {
            return list;
        }
        if (raw instanceof Object[] array) {
            return Arrays.asList(array);
        }
        return List.of();
    }

    private List<?> findValue(List<?> fields, String name) {
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            if (name.equals(text(fields.get(i)))) {
                return asList(fields.get(i + 1));
            }
        }
        return List.of();
    }

    private double distanceToSimilarity(double distance) {
        if (Double.isNaN(distance) || Double.isInfinite(distance)) {
            return 0;
        }
        return Math.max(0, Math.min(1, 1 - distance));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
