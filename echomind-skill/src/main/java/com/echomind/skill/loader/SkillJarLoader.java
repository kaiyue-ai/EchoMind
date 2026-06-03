package com.echomind.skill.loader;

import com.echomind.common.exception.SkillLoadException;
import com.echomind.skill.api.Skill;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Skill JAR文件加载器，负责从JAR文件中发现并实例化Skill。
 *
 * <p>加载过程：</p>
 * <ol>
 *   <li>验证JAR文件是否存在</li>
 *   <li>读取JAR的{@code META-INF/MANIFEST.MF}清单文件</li>
 *   <li>从清单中提取{@code EchoMind-Skill-Class}属性——Skill实现类的全限定名</li>
 *   <li>创建{@link SkillClassLoader}隔离类加载器</li>
 *   <li>通过类加载器加载Skill类，验证其实现了{@link Skill}接口</li>
 *   <li>通过反射调用无参构造器实例化Skill</li>
 *   <li>封装为{@link SkillLoadResult}返回</li>
 * </ol>
 *
 * <p>清单规范：</p>
 * <pre>
 * Manifest-Version: 1.0
 * EchoMind-Skill-Class: com.example.weather.WeatherSkill
 * EchoMind-Skill-Version: 1.0.0
 * </pre>
 *
 * <p>异常处理：</p>
 * <ul>
 *   <li>所有加载错误统一包装为{@link SkillLoadException}</li>
 *   <li>如果Skill类未实现{@link Skill}接口，立即关闭类加载器防止资源泄漏</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillClassLoader
 * @see SkillLoadResult
 */
@Slf4j // 这是负责编排类加载器的
public class SkillJarLoader {

    /**
     * MANIFEST.MF中的属性名，用于指定Skill实现类的全限定名。
     * Skill开发者需在JAR打包时设置此清单属性。
     */
    private static final String SKILL_CLASS_HEADER = "EchoMind-Skill-Class";

    /**
     * 从JAR文件加载Skill。
     *
     * <p>此方法执行完整的JAR到Skill实例的转换流程，包括清单验证、
     * 类加载、接口验证和反射实例化。</p>
     *
     * @param jarPath Skill JAR文件的路径
     * @return SkillLoadResult 包含Skill实例、类加载器和JAR路径的结果对象
     * @throws SkillLoadException 如果JAR不存在、清单缺失、Skill类无效或加载过程中发生错误
     */
    public SkillLoadResult load(Path jarPath) {
        // 判断这个文件存不存在
        if (!Files.exists(jarPath)) {
            throw new SkillLoadException("Skill JAR not found: " + jarPath);
        }

        // 读取JAR的清单文件
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // 读取清单文件 manifest是JAR文件的清单文件,包含了JAR文件的信息,比如类名,版本号等
            Manifest manifest = jar.getManifest(); // 就是元数据
            if (manifest == null) {
                throw new SkillLoadException("No MANIFEST.MF in skill JAR: " + jarPath);
            }

            // 从清单中提取skill实现类的全限定名
            String skillClass = manifest.getMainAttributes().getValue(SKILL_CLASS_HEADER);
            if (skillClass == null || skillClass.isBlank()) {
                throw new SkillLoadException("No " + SKILL_CLASS_HEADER + " in manifest: " + jarPath);
            }
            // 为类加载器准备jar文件的路径,用于加载类时指定jar文件的路径
            URL[] urls = new URL[]{ jarPath.toUri().toURL() };
            // 进行类加载,创建SkillClassLoader隔离类加载器
            SkillClassLoader classLoader = new SkillClassLoader(urls, getClass().getClassLoader());

            // 根据这个类的路径来加载这个类,返回加载后的类对象
            Class<?> loaded = classLoader.loadClass(skillClass.trim());
            // 验证其实现了Skill接口
            if (!Skill.class.isAssignableFrom(loaded)) {
                // 如果没有实现Skill接口,则关闭类加载器防止资源泄漏
                classLoader.close();
                throw new SkillLoadException("Class does not implement Skill: " + skillClass);
            }
            // 通过反射调用无参构造器实例化Skill,返回实例化后的Skill对象
            Skill skill = (Skill) loaded.getDeclaredConstructor().newInstance();
            // 封装为SkillLoadResult返回
            return new SkillLoadResult(skill, classLoader, jarPath);

        } catch (SkillLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillLoadException("Failed to load skill JAR: " + jarPath, e);
        }
    }

    /**
     * Skill加载结果记录，封装一次成功加载的所有产物。
     *
     * @param skill       实例化后的Skill对象
     * @param classLoader 隔离类加载器，用于后续卸载
     * @param jarPath     源JAR文件的路径
     */
    public record SkillLoadResult(Skill skill, SkillClassLoader classLoader, Path jarPath) {}
}
