package com.echomind.skill.storage;

/**
 * 对象存储写入结果。
 *
 * <p>{@code uri} 是系统内部长期保存的稳定地址，{@code url} 是前端展示或模型读取时
 * 使用的访问地址。OSS 私有桶通常返回签名 URL，本地模式返回后端可代理访问的 URL。</p>
 */
public record StoredObject(
    String uri,
    String bucket,
    String key,
    String url,
    long size,
    String contentType
) {}
