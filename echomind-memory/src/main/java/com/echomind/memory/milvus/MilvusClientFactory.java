package com.echomind.memory.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Shared Milvus client factory for vector-backed memory stores.
 */
@Slf4j
public class MilvusClientFactory {

    private final MilvusServiceClient client;

    public MilvusClientFactory(String host, int port) {
        this.client = new MilvusServiceClient(
            ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(10_000, TimeUnit.MILLISECONDS)
                .withKeepAliveTime(60_000, TimeUnit.MILLISECONDS)
                .build()
        );
        log.info("Milvus client configured for {}:{}", host, port);
    }

    public MilvusServiceClient getClient() {
        return client;
    }

    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close Milvus client: {}", e.getMessage());
        }
    }
}
