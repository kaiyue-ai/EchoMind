package com.echomind.memory.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;

/**
 * Milvus 客户端工厂。
 *
 * <p>封装 MilvusServiceClient 的创建和生命周期管理，
 * 确保整个应用共享同一个客户端实例。</p>
 */
@Slf4j
public class MilvusClientFactory {

    private final MilvusServiceClient client;

    public MilvusClientFactory(String host, int port) {
        this.client = new MilvusServiceClient(
            ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .withKeepAliveTime(60_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        );
        log.info("Milvus client connected to {}:{}", host, port);
    }

    public MilvusServiceClient getClient() {
        return client;
    }

    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Failed to close Milvus client: {}", e.getMessage());
            }
        }
    }
}
