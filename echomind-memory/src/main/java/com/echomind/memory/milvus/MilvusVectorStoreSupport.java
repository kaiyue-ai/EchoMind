package com.echomind.memory.milvus;

import io.milvus.grpc.CollectionSchema;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
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
import java.util.Optional;

/**
 * Shared Milvus helpers for collection management and vector operations.
 */
@Slf4j
public final class MilvusVectorStoreSupport {

    private MilvusVectorStoreSupport() {
    }

    public static boolean hasCollection(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getData() == Boolean.TRUE;
    }

    public static Optional<CollectionSchema> describeCollection(MilvusServiceClient client, String collectionName) {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        if (response.getStatus() != R.Status.Success.getCode() || response.getData() == null
            || !response.getData().hasSchema()) {
            log.warn("Milvus describe collection {} failed: {}", collectionName, response.getMessage());
            return Optional.empty();
        }
        return Optional.of(response.getData().getSchema());
    }

    public static boolean dropCollection(MilvusServiceClient client, String collectionName) {
        if (!hasCollection(client, collectionName)) {
            return true;
        }
        R<RpcStatus> response = client.dropCollection(DropCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    public static boolean loadCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.loadCollection(LoadCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

    public static boolean releaseCollection(MilvusServiceClient client, String collectionName) {
        R<RpcStatus> response = client.releaseCollection(ReleaseCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .build());
        return response.getStatus() == R.Status.Success.getCode();
    }

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

    public static List<Float> toFloatList(double[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (double value : vector) {
            list.add((float) value);
        }
        return list;
    }

    public static double cosineScoreToSimilarity(float score) {
        if (Float.isNaN(score) || Float.isInfinite(score)) {
            return 0;
        }
        double similarity = clamp(score, 0, 1);
        return Math.round(similarity * 1_000_000d) / 1_000_000d;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static FieldType pkField(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int64)
            .withAutoID(true)
            .withPrimaryKey(true)
            .build();
    }

    public static FieldType varCharField(String name, int maxLength) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.VarChar)
            .withMaxLength(maxLength)
            .build();
    }

    public static FieldType int64Field(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int64)
            .build();
    }

    public static FieldType int32Field(String name) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.Int32)
            .build();
    }

    public static FieldType floatVectorField(String name, int dimension) {
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.FloatVector)
            .withDimension(dimension)
            .build();
    }

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
