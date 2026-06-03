package com.echomind.memory.usermemory;

import com.echomind.memory.usermemory.impl.MilvusUserMemoryStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusUserMemoryStoreTest {

    @Test
    void searchRejectsBlankInputsBeforeTouchingMilvus() {
        MilvusUserMemoryStore store = new MilvusUserMemoryStore(
            mock(MilvusServiceClient.class),
            "test_user_memory"
        );

        List<UserMemoryHit> hits = store.search("", new double[] {0.1}, 5, 0.3, 0.65);

        assertThat(hits).isEmpty();
    }

    @Test
    void saveWritesObservationTimestampsToMilvus() {
        MilvusServiceClient client = mock(MilvusServiceClient.class);
        when(client.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(true));
        when(client.loadCollection(any(LoadCollectionParam.class))).thenReturn(R.success(new RpcStatus(RpcStatus.SUCCESS_MSG)));
        when(client.insert(any(InsertParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));
        MilvusUserMemoryStore store = new MilvusUserMemoryStore(client, "test_user_memory");
        Instant firstObservedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant lastObservedAt = Instant.parse("2026-01-02T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-03T00:00:00Z");

        store.save(new UserMemoryEntry(
            "user:user-a",
            "entry-1",
            UserMemoryCategory.PREFERENCE,
            "用户偏好中文注释",
            "用户明确提出",
            0.9,
            firstObservedAt,
            lastObservedAt,
            updatedAt,
            new double[] {0.1, 0.2}
        ));

        ArgumentCaptor<InsertParam> insertCaptor = ArgumentCaptor.forClass(InsertParam.class);
        verify(client).insert(insertCaptor.capture());
        Map<String, Object> values = insertCaptor.getValue().getFields().stream()
            .collect(Collectors.toMap(InsertParam.Field::getName, field -> field.getValues().get(0)));
        assertThat(values)
            .containsEntry("first_observed_at", firstObservedAt.toEpochMilli())
            .containsEntry("last_observed_at", lastObservedAt.toEpochMilli())
            .containsEntry("updated_at", updatedAt.toEpochMilli());
    }
}
