package com.echomind.skill.loader;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

@Slf4j
public class SkillClassLoader extends URLClassLoader {

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
    // 自定义类加载器 : Class实例唯一性=类加载器实例 * 类的全限定名
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
