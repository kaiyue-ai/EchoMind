package com.echomind.skill.loader;

import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * Skill隔离类加载器，为每个Skill提供独立的类加载空间。
 *
 * <p>核心设计——双亲委托策略（Parent-First + Self-First 混合模式）：</p>
 * <ul>
 *   <li><b>Parent-First（父类优先）</b>：以下包路径优先从父类加载器加载，确保平台与Skill共享相同的类定义：
 *     <ul>
 *       <li>{@code java.*} / {@code javax.*} — JDK标准库，必须由引导类加载器加载</li>
 *       <li>{@code com.echomind.skill.api.*} — Skill SPI接口，确保类型兼容</li>
 *       <li>{@code com.echomind.mcp.*} — MCP协议类</li>
 *       <li>{@code com.fasterxml.jackson.*} — JSON序列化库</li>
 *       <li>{@code org.slf4j.*} / {@code ch.qos.logback.*} — 日志框架</li>
 *     </ul>
 *   </li>
 *   <li><b>Self-First（自身优先）</b>：其他所有类（包括Skill自身的实现类和第三方依赖库）优先从Skill JAR中加载，实现真正的隔离</li>
 * </ul>
 *
 * <p>类加载流程（{@link #loadClass(String, boolean)}）：</p>
 * <ol>
 *   <li>检查类是否已加载（{@link #findLoadedClass(String)}）</li>
 *   <li>如果属于Parent-First包，委托父类加载器</li>
 *   <li>尝试从Skill JAR中加载（{@link #findClass(String)}）</li>
 *   <li>如果Self-First且自身加载失败，回退到父类加载器</li>
 * </ol>
 *
 * <p>资源管理：</p>
 * <ul>
 *   <li>实现了{@link Closeable}接口，支持try-with-resources语法</li>
 *   <li>关闭时释放底层URLClassLoader资源</li>
 * </ul>
 *
 * <p>设计依据：OSGi和Java模块化系统的最佳实践——平台API类共享，Skill实现类隔离，
 * 防止不同Skill的依赖冲突，同时确保Skill能正确与平台交互。</p>
 *
 * @author EchoMind Team
 * @see SkillJarLoader
 */
@Slf4j
public class SkillClassLoader extends URLClassLoader implements Closeable {

    /**
     * 父类优先的包前缀集合。
     *
     * <p>这些包路径下的类将优先从父类加载器加载，而非从Skill JAR加载。
     * 设计原则：平台API和JDK类需要共享，Skill实现和第三方依赖需要隔离。</p>
     */
    private static final Set<String> PARENT_FIRST_PACKAGES = Set.of(
        "java.", "javax.", "com.echomind.skill.api.", "com.echomind.mcp.",
        "com.fasterxml.jackson.", "org.slf4j.", "ch.qos.logback."
    );

    /**
     * 构造Skill隔离类加载器。
     *
     * @param urls   Skill JAR的URL数组，用于查找类资源
     * @param parent 父类加载器，通常是应用类加载器
     */
    public SkillClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    /**
     * 核心类加载方法，实现混合双亲委托策略。
     *
     * <p>加载逻辑：</p>
     * <ol>
     *   <li>通过{@link #getClassLoadingLock(String)}获取类级别的加载锁，保证线程安全</li>
     *   <li>检查类是否已被此加载器加载</li>
     *   <li>判断类名是否属于Parent-First包路径</li>
     *   <li>Parent-First：先委托父类加载器，成功则返回</li>
     *   <li>尝试从Skill JAR自身加载</li>
     *   <li>Self-First且自身加载失败：回退到父类加载器</li>
     * </ol>
     *
     * @param name    类的全限定名
     * @param resolve 是否在加载后立即解析（链接）该类
     * @return 加载的Class对象
     * @throws ClassNotFoundException 如果类在父类加载器和自身JAR中都找不到
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            boolean parentFirst = isParentFirst(name);

            if (parentFirst) {
                try {
                    return super.loadClass(name, resolve);
                } catch (ClassNotFoundException e) {
                    // 父类加载失败，回退到自身加载
                }
            }

            try {
                Class<?> clazz = findClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException e) {
                if (!parentFirst) {
                    return super.loadClass(name, resolve);
                }
                throw e;
            }
        }
    }

    /**
     * 判断给定的类名是否属于父类优先加载的包路径。
     *
     * @param className 类的全限定名
     * @return 如果属于Parent-First包则返回true
     */
    private boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 关闭类加载器，释放底层资源。
     *
     * <p>关闭时捕获并记录所有IO异常，不会向上层抛出。</p>
     */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } catch (IOException e) {
            log.warn("Error closing skill classloader", e);
        }
    }
}
