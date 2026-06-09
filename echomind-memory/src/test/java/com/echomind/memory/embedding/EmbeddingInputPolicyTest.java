package com.echomind.memory.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingInputPolicyTest {

    @Test
    void keepsShortQueryUnchangedAfterWhitespaceNormalization() {
        EmbeddingInputPolicy policy = new EmbeddingInputPolicy(100);

        String query = policy.safeQuery("  查询\tEchoMind   Milvus\r\n状态  ");

        assertThat(query).isEqualTo("查询 EchoMind Milvus\n状态");
    }

    @Test
    void trimsOverlongQueryByKeepingHeadAndTail() {
        EmbeddingInputPolicy policy = new EmbeddingInputPolicy(30);

        String query = policy.safeQuery("HEAD-abcdefghijklmnopqrstuvwxyz-TAIL");

        assertThat(query).hasSize(30);
        assertThat(query).startsWith("HEAD-");
        assertThat(query).contains("\n...\n");
        assertThat(query).endsWith("-TAIL");
    }
}
