package com.echomind.skill.filesystem;

import com.echomind.skill.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 文件系统技能 —— 提供安全的文件系统读写和目录列表操作。
 *
 * <p>本技能兼有双重模式：
 * <ul>
 *   <li><b>标准 Skill 模式</b>：通过 EchoMind 技能管线直接调用</li>
 *   <li><b>MCP 兼容模式</b>：同时可作为 MCP 工具被外部客户端发现和调用</li>
 * </ul>
 *
 * <p>支持三种文件操作：
 * <ol>
 *   <li><b>read</b> —— 读取指定路径的文件内容并返回</li>
 *   <li><b>write</b> —— 将内容写入指定路径（创建或覆盖）</li>
 *   <li><b>list</b> —— 列出指定目录下的所有条目</li>
 * </ol>
 *
 * <p>安全机制 — 沙箱路径限制：
 * <ul>
 *   <li>仅允许访问三个根目录下的文件：
 *     <ol>
 *       <li>用户主目录（{@code user.home}）</li>
 *       <li>系统临时目录（{@code java.io.tmpdir}）</li>
 *       <li>应用数据目录（{@code ./data}）</li>
 *     </ol>
 *   </li>
 *   <li>通过 {@link #isAllowed} 方法进行路径归属验证，
 *       使用 {@link Path#toRealPath()} 解析符号链接防止路径穿越攻击。</li>
 *   <li>任何越权访问均返回 "Access denied" 错误。</li>
 * </ul>
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code operation}（string，必填）—— 操作类型：read / write / list</li>
 *   <li>{@code path}（string，必填）—— 目标文件或目录路径</li>
 *   <li>{@code content}（string，write 操作可选）—— 要写入的内容</li>
 * </ul>
 *
 * <p>技能标签：file, filesystem, read, write, directory, 文件, 读写, 目录, 文件系统, 读文件, 写文件
 */
public class FilesystemSkill implements Skill {

    /**
     * 允许访问的根目录白名单。
     *
     * <p>所有文件操作的目标路径必须在这些根目录之下。
     * 通过真实路径（toRealPath）比较防止符号链接绕过。
     */
    private static final Set<String> ALLOWED_ROOTS = Set.of(
        System.getProperty("user.home"),
        System.getProperty("java.io.tmpdir"),
        "./data"
    );

    /**
     * 返回技能元数据。
     *
     * <p>定义三个输入参数（operation / path / content）的 Schema，
     * 标记依赖 echomind-mcp 模块以实现 MCP 兼容。
     *
     * @return 文件系统技能的完整元数据
     */
    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "filesystem",
            "1.0.0",
            "File system operations: read, write, list files. MCP compatible.",
            Map.of(
                "properties", Map.of("operation", Map.of("type", "string",
                    "enum", List.of("read", "write", "list")),
                    "path", Map.of("type", "string", "description", "File or directory path"),
                    "content", Map.of("type", "string", "description", "Content to write (for write operation)")
                ),
                "required", List.of("operation", "path")
            ),
            List.of("echomind-mcp"),
            "EchoMind",
            List.of("file", "filesystem", "read", "write", "directory", "文件", "读写", "目录", "文件系统", "读文件", "写文件")
        );
    }

    /**
     * 执行文件系统操作。
     *
     * <p>流程：
     * <ol>
     *   <li>提取操作类型和路径参数</li>
     *   <li>验证路径是否在允许的根目录范围内</li>
     *   <li>根据操作类型分发到对应的处理方法</li>
     * </ol>
     *
     * @param request 技能请求，包含 operation、path 和可选的 content 参数
     * @return 包含操作结果或错误信息的异步结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String operation = String.valueOf(request.parameters().get("operation"));
                String path = String.valueOf(request.parameters().get("path"));

                if (!isAllowed(path)) {
                    return SkillResult.failure("Access denied: " + path, System.currentTimeMillis() - start);
                }

                return switch (operation) {
                    case "read" -> readFile(path, start);
                    case "write" -> writeFile(path, String.valueOf(
                        request.parameters().getOrDefault("content", "")), start);
                    case "list" -> listDirectory(path, start);
                    default -> SkillResult.failure("Unknown operation: " + operation,
                        System.currentTimeMillis() - start);
                };
            } catch (Exception e) {
                return SkillResult.failure("Filesystem error: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    /**
     * 验证目标路径是否在允许的根目录范围内。
     *
     * <p>路径存在时通过 {@link Path#toRealPath()} 解析符号链接防止路径穿越攻击；
     * 路径不存在时（如 write 操作创建新文件），逐步向上查找已存在的父目录进行校验。
     *
     * @param path 待验证的文件路径（字符串形式）
     * @return true 表示路径在允许范围内；false 表示拒绝访问或路径无效
     */
    private boolean isAllowed(String path) {
        try {
            Path target = Paths.get(path);
            // 如果路径已存在，用 toRealPath 解析符号链接
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                return isUnderAllowedRoot(real);
            }
            // 路径不存在时，用 normalize + toAbsolutePath 解析
            Path normalized = target.normalize().toAbsolutePath();
            // 找到最近的已存在父目录
            Path parent = normalized.getParent();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            if (parent != null) {
                Path realParent = parent.toRealPath();
                // 用已存在的父目录的真实路径 + 剩余相对路径重构完整真实路径
                Path remaining = parent.relativize(normalized);
                Path resolvedReal = realParent.resolve(remaining).normalize();
                return isUnderAllowedRoot(resolvedReal);
            }
            // 没有已存在的父目录（极端情况），使用绝对路径直接判断
            return isUnderAllowedRoot(normalized);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查规范化后的路径是否以任一允许的根目录为前缀。
     */
    private boolean isUnderAllowedRoot(Path normalized) throws IOException {
        for (String root : ALLOWED_ROOTS) {
            Path rootPath = Paths.get(root);
            Path realRoot = Files.exists(rootPath) ? rootPath.toRealPath() : rootPath.normalize().toAbsolutePath();
            if (normalized.startsWith(realRoot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取文件内容。
     *
     * <p>使用 {@link Files#readString} 一次性读取整个文件。
     * 注意：对于超大文件可能消耗大量内存。
     *
     * @param path  文件路径
     * @param start 操作开始时间戳（ms），用于计算耗时
     * @return 包含文件内容的成功结果
     * @throws IOException 文件读取 I/O 错误
     */
    private SkillResult readFile(String path, long start) throws IOException {
        String content = Files.readString(Paths.get(path));
        return SkillResult.success(content, System.currentTimeMillis() - start);
    }

    /**
     * 写入文件内容。
     *
     * <p>使用 {@link Files#writeString} 原子性地写入内容。
     * 设置 {@code CREATE} 和 {@code TRUNCATE_EXISTING} 选项：
     * 创建不存在的文件，覆盖已存在的文件。
     *
     * @param path    文件路径
     * @param content 要写入的内容
     * @param start   操作开始时间戳（ms），用于计算耗时
     * @return 包含写入确认信息的成功结果
     * @throws IOException 文件写入 I/O 错误
     */
    private SkillResult writeFile(String path, String content, long start) throws IOException {
        Files.writeString(Paths.get(path), content,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return SkillResult.success("Written to " + path, System.currentTimeMillis() - start);
    }

    /**
     * 列出目录内容。
     *
     * <p>使用 {@link DirectoryStream} 进行高效的目录遍历，
     * 每行输出一个条目名称。不递归进入子目录。
     *
     * @param path  目录路径
     * @param start 操作开始时间戳（ms），用于计算耗时
     * @return 包含目录条目列表（每行一个文件名）的成功结果
     * @throws IOException 目录访问 I/O 错误
     */
    private SkillResult listDirectory(String path, long start) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(path))) {
            for (Path entry : stream) {
                sb.append(entry.getFileName()).append("\n");
            }
        }
        return SkillResult.success(sb.toString(), System.currentTimeMillis() - start);
    }
}
