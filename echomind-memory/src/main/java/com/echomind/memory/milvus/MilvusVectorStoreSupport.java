package com.echomind.memory.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量存储公共工具方法。
 *
 * <p>提供 Collection 创建、索引管理、向量写入/检索/删除等基础操作，
 * 被 MilvusUserMemoryStore 和 AgentKnowledgeService 共用。</p>
 */
@Slf4j
public final class MilvusVectorStoreSupport {

    private MilvusVectorStoreSupport() {
    }

    // ── Collection 管理 ──

    /** 检查 Collection 是否存在。 */
    public static boolean hasCollection(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getData() == Boolean.TRUE;
    }

    /** 删除 Collection。 */
    public static boolean dropCollection(MilvusServiceClient client, String collectionName) {
        if (!hasCollection(client, collectionName)) {
            return true;
        }
        R<RpcStatus> response = client.dropCollection(DropCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    /** 加载 Collection 到内存。 */
    public static boolean loadCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.loadCollection(LoadCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    /** 释放 Collection 内存。 */
    public static boolean releaseCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.releaseCollection(ReleaseCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    /** 创建 HNSW 向量索引。 */
    public static boolean createHnswIndex(MilvusServiceClient client, String collectionName,
                                           String fieldName, String indexName) {
        R<RpcStatus> response = client.createIndex(CreateIndexParam.newBuilder()
            .withCollectionName(collectionName)
            .withFieldName(fieldName)
            .withIndexName(indexName)
            .withIndexType(IndexType.HNSW)
            .withMetricType(MetricType.COSINE)
            .withExtraParam("{\"M\":16,\"efConstruction\":256}")
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    // ── 数据操作 ──

    /** 插入数据。 */
    public static boolean insert(MilvusServiceClient client, String collectionName,
                                  List<InsertParam.Field> fields) {
        R<MutationResult> response = client.insert(InsertParam.newBuilder()
            .withCollectionName(collectionName)
            .withFields(fields)
            .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus insert failed: {}", response.getMessage());
            return false;
        }
        return true;
    }

    /** 按表达式删除数据。 */
    public static boolean delete(MilvusServiceClient client, String collectionName, String expr) {
        R<MutationResult> response = client.delete(DeleteParam.newBuilder()
            .withCollectionName(collectionName)
            .withExpr(expr)
            .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus delete failed: {}", response.getMessage());
            return false;
        }
        return true;
    }

    /** 向量检索。 */
    public static List<SearchResultsWrapper.IDScore> search(MilvusServiceClient client,
                                                             String collectionName,
                                                             List<String> outputFields,
                                                             String expr,
                                                             List<List<Float>> vectors,
                                                             String vectorFieldName,
                                                             int topK) {
        R<SearchResults> response = client.search(SearchParam.newBuilder()
            .withCollectionName(collectionName)
            .withOutFields(outputFields)
            .withExpr(expr)
            .withVectors(vectors)
            .withVectorFieldName(vectorFieldName)
            .withTopK(topK)
            .withMetricType(MetricType.COSINE)
            .withParams("{\"ef\":128}")
            .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus search failed: {}", response.getMessage());
            return Collections.emptyList();
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        return wrapper.getIDScore(0);
    }

    // ── 向量转换 ──

    /** double[] 转 List<Float>，Milvus SDK 要求的格式。 */
    public static List<Float> toFloatList(double[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (double v : vector) {
            list.add((float) v);
        }
        return list;
    }

    /** COSINE 距离转相似度 (0~1)。 */
    public static double distanceToSimilarity(float distance) {
        if (Float.isNaN(distance) || Float.isInfinite(distance)) {
            return 0;
        }
        double similarity = clamp(1d - distance, 0, 1);
        return Math.round(similarity * 1_000_000d) / 1_000_000d;
    }

    /** Milvus COSINE search score 本身就是相似度，值越大越相近。 */
    public static double cosineScoreToSimilarity(float score) {
        if (Float.isNaN(score) || Float.isInfinite(score)) {
            return 0;
        }
        double similarity = clamp(score, -1, 1);
        return Math.round(similarity * 1_000_000d) / 1_000_000d;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── FieldType 构建辅助 ──

    /** 构建主键字段（Int64 自增）。 */
    public static FieldType pkField(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int64)
            .withAutoID(true)
            .withPrimaryKey(true)
            .build();
    }

    /** 构建 VarChar 字段。 */
    public static FieldType varCharField(String name, int maxLength) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.VarChar)
            .withMaxLength(maxLength)
            .build();
    }

    /** 构建 Int64 字段。 */
    public static FieldType int64Field(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int64)
            .build();
    }

    /** 构建 Float 字段。 */
    public static FieldType floatField(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Float)
            .build();
    }

    /** 构建 Int32 字段。 */
    public static FieldType int32Field(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int32)
            .build();
    }

    /** 构建 FloatVector 字段。 */
    public static FieldType floatVectorField(String name, int dimension) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.FloatVector)
            .withDimension(dimension)
            .build();
    }

    /** 创建 Collection 的便捷方法。 */
    public static boolean createCollection(MilvusServiceClient client, String collectionName,
                                            List<FieldType> fields, String description) {
        CreateCollectionParam.Builder builder = CreateCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .withDescription(description)
            .withShardsNum(2);
        for (FieldType field : fields) {
            builder.addFieldType(field);
        }
        R<RpcStatus> response = client.createCollection(builder.build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus create collection {} failed: {}", collectionName, response.getMessage());
            return false;
        }
        log.info("Created Milvus collection {}", collectionName);
        return true;
    }
}
