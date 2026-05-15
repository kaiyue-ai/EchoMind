package com.echomind.memory.usermemory;

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

/** Redis Stack 用户长期画像向量存储。 */
@Slf4j
public class UserMemoryVectorStore implements UserMemoryStore {

    private static final byte[] FIELD_SESSION_ID = bytes("session_id");
    private static final byte[] FIELD_ENTRY_ID = bytes("entry_id");
    private static final byte[] FIELD_CATEGORY = bytes("category");
    private static final byte[] FIELD_CONTENT = bytes("content");
    private static final byte[] FIELD_EVIDENCE = bytes("evidence");
    private static final byte[] FIELD_CONFIDENCE = bytes("confidence");
    private static final byte[] FIELD_EMBEDDING = bytes("embedding");

    private final RedisConnectionFactory connectionFactory;
    private final RedisKeyScanner keyScanner;
    private final String indexName;
    private final String keyPrefix;

    private volatile boolean indexReady;
    private volatile int indexDimension;

    public UserMemoryVectorStore(RedisConnectionFactory connectionFactory, String indexName, String keyPrefix) {
        this.connectionFactory = connectionFactory;
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        this.keyScanner = new RedisKeyScanner(redis);
        this.indexName = blankToDefault(indexName, "idx:user:memory:vectors");
        this.keyPrefix = blankToDefault(keyPrefix, "user:memory:vector:");
    }

    @Override
    public void save(UserMemoryEntry entry) {
        if (entry == null || entry.embedding() == null || entry.embedding().length == 0) {
            return;
        }
        ensureIndex(entry.embedding().length);
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.execute("HSET",
                bytes(key(entry.sessionId(), entry.entryId())),
                FIELD_SESSION_ID, bytes(entry.sessionId()),
                FIELD_ENTRY_ID, bytes(entry.entryId()),
                FIELD_CATEGORY, bytes(entry.category().storageValue()),
                FIELD_CONTENT, bytes(entry.content()),
                FIELD_EVIDENCE, bytes(entry.evidence()),
                FIELD_CONFIDENCE, bytes(String.valueOf(clamp(entry.confidence(), 0, 1))),
                FIELD_EMBEDDING, vectorBytes(entry.embedding())
            );
        }
    }

    @Override
    public List<UserMemoryHit> search(String sessionId, double[] queryVector, int topK, double minConfidence) {
        if (sessionId == null || sessionId.isBlank() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        try {
            ensureIndex(queryVector.length);
            String query = "(@session_id:{" + escapeTag(sessionId) + "})=>[KNN "
                + topK + " @embedding $vec AS score]";
            Object raw;
            try (RedisConnection connection = connectionFactory.getConnection()) {
                raw = executeArrayCommand(connection, "FT.SEARCH",
                    bytes(indexName),
                    bytes(query),
                    bytes("PARAMS"), bytes("2"), bytes("vec"), vectorBytes(queryVector),
                    bytes("SORTBY"), bytes("score"), bytes("ASC"),
                    bytes("RETURN"), bytes("6"), bytes("entry_id"), bytes("category"), bytes("content"),
                    bytes("evidence"), bytes("confidence"), bytes("score"),
                    bytes("DIALECT"), bytes("2")
                );
            }
            return parseSearchResult(raw, minConfidence);
        } catch (Exception e) {
            log.warn("Redis Stack user memory search failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<UserMemoryHit> listBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            Object raw;
            try (RedisConnection connection = connectionFactory.getConnection()) {
                raw = executeArrayCommand(connection, "FT.SEARCH",
                    bytes(indexName),
                    bytes("@session_id:{" + escapeTag(sessionId) + "}"),
                    bytes("RETURN"), bytes("5"), bytes("entry_id"), bytes("category"), bytes("content"),
                    bytes("evidence"), bytes("confidence"),
                    bytes("LIMIT"), bytes("0"), bytes(String.valueOf(limit)),
                    bytes("DIALECT"), bytes("2")
                );
            }
            return parseSearchResult(raw, 0);
        } catch (Exception e) {
            log.debug("Redis Stack user memory list failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteEntry(String sessionId, String entryId) {
        if (sessionId == null || sessionId.isBlank() || entryId == null || entryId.isBlank()) {
            return;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.execute("DEL", bytes(key(sessionId, entryId)));
        } catch (Exception e) {
            log.warn("Failed to delete user memory entry sessionId={} entryId={}: {}",
                sessionId, entryId, e.getMessage());
        }
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            keyScanner.deleteByPattern(keyPrefix + encodedSessionId(sessionId) + ":*");
        } catch (Exception e) {
            log.warn("Failed to delete user memory vectors for sessionId={}: {}", sessionId, e.getMessage());
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
                bytes("entry_id"), bytes("TEXT"),
                bytes("category"), bytes("TAG"),
                bytes("content"), bytes("TEXT"),
                bytes("evidence"), bytes("TEXT"),
                bytes("confidence"), bytes("NUMERIC"),
                bytes("embedding"), bytes("VECTOR"), bytes("HNSW"), bytes("6"),
                bytes("TYPE"), bytes("FLOAT32"),
                bytes("DIM"), bytes(String.valueOf(dimension)),
                bytes("DISTANCE_METRIC"), bytes("COSINE")
            );
            indexReady = true;
            indexDimension = dimension;
            log.info("Created Redis Stack user memory index {} with dimension {}", indexName, dimension);
        } catch (Exception e) {
            if (exceptionMessage(e).contains("already exists")) {
                indexReady = true;
                indexDimension = dimension;
                return;
            }
            indexReady = false;
            throw e;
        }
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

    private List<UserMemoryHit> parseSearchResult(Object raw, double minConfidence) {
        List<?> rows = asList(raw);
        if (rows.size() <= 1) {
            return List.of();
        }
        List<?> structuredResults = findValue(rows, "results");
        if (!structuredResults.isEmpty()) {
            return parseStructuredResults(structuredResults, minConfidence);
        }
        List<UserMemoryHit> hits = new ArrayList<>();
        for (int i = 1; i + 1 < rows.size(); i += 2) {
            Object fieldsRaw = rows.get(i + 1);
            List<?> fields = asList(fieldsRaw);
            if (fields.isEmpty()) {
                continue;
            }
            UserMemoryHit hit = parseFields(fields, minConfidence);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private List<UserMemoryHit> parseStructuredResults(List<?> results, double minConfidence) {
        List<UserMemoryHit> hits = new ArrayList<>();
        for (Object result : results) {
            List<?> resultFields = asList(result);
            if (resultFields.isEmpty()) {
                continue;
            }
            List<?> extraAttributes = findValue(resultFields, "extra_attributes");
            UserMemoryHit hit = parseFields(extraAttributes, minConfidence);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private UserMemoryHit parseFields(List<?> fields, double minConfidence) {
        String entryId = null;
        UserMemoryCategory category = UserMemoryCategory.INTEREST;
        String content = null;
        String evidence = "";
        double confidence = 0;
        double score = 0;
        for (int j = 0; j + 1 < fields.size(); j += 2) {
            String name = text(fields.get(j));
            Object value = fields.get(j + 1);
            if ("entry_id".equals(name)) {
                entryId = text(value);
            } else if ("category".equals(name)) {
                category = UserMemoryCategory.from(text(value));
            } else if ("content".equals(name)) {
                content = text(value);
            } else if ("evidence".equals(name)) {
                evidence = text(value);
            } else if ("confidence".equals(name)) {
                confidence = parseDouble(value);
            } else if ("score".equals(name)) {
                score = distanceToSimilarity(parseDouble(value));
            }
        }
        if (entryId != null && content != null && confidence >= minConfidence) {
            return new UserMemoryHit(entryId, category, content, evidence, confidence, score);
        }
        return null;
    }

    private byte[] vectorBytes(double[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : vector) {
            buffer.putFloat((float) value);
        }
        return buffer.array();
    }

    private String key(String sessionId, String entryId) {
        return keyPrefix + encodedSessionId(sessionId) + ":" + entryId;
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
