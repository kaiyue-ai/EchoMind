package com.echomind.skill.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 对象存储统一接口。
 *
 * <p>Skill JAR、聊天图片等二进制文件都通过这里写入。上层只保存 URI，
 * 不关心背后是阿里云 OSS 还是本地兜底目录。</p>
 */
public interface ObjectStorageService {

    /** 上传文件并返回稳定 URI 和可访问 URL。 */
    StoredObject putObject(String key, Path file, String contentType) throws IOException;

    /** 删除对象；对象不存在时应保持幂等。 */
    void deleteObject(String uriOrKey) throws IOException;

    /** 读取对象内容，供需要内联二进制数据的模型调用链路使用。 */
    byte[] readObject(String uriOrKey) throws IOException;

    /** 为对象生成访问 URL。 */
    String urlFor(String uriOrKey, Duration ttl);

    /** 判断当前服务是否能处理这个 URI。 */
    boolean supports(String uriOrKey);

    /** 存储模式名称，例如 oss 或 local。 */
    String mode();
}
