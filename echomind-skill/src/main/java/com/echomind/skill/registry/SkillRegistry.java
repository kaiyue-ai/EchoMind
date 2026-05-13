package com.echomind.skill.registry;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.Skill;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill注册中心，作为平台所有Skill的中枢注册表。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>管理Skill的生命周期（注册、启用、禁用、卸载）</li>
 *   <li>提供按ID或名称查询Skill的能力</li>
 *   <li>维护每个Skill的{@link SkillRegistration}元数据，包括Skill实例、类加载器和状态</li>
 * </ul>
 *
 * <p>线程安全设计：</p>
 * <ul>
 *   <li>使用{@link ConcurrentHashMap}存储注册信息，支持高并发读写</li>
 *   <li>注册/注销操作是原子性的，但生命周期回调（onEnable/onDisable/onDestroy）在锁外执行</li>
 * </ul>
 *
 * <p>状态管理：</p>
 * <ul>
 *   <li>{@link SkillState#LOADED} — Skill已加载但尚未启用</li>
 *   <li>{@link SkillState#ENABLED} — Skill已启用，可接受执行请求</li>
 *   <li>{@link SkillState#DISABLED} — Skill已禁用，不会被查询到</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillRegistration
 * @see SkillState
 */
@Slf4j
public class SkillRegistry {

    /**
     * Skill注册表，以skillId为键、SkillRegistration为值的线程安全映射。
     * 键为Skill的唯一标识符（来自Skill.metadata().skillId()），
     * 值为包含Skill实例、元数据、类加载器和运行时状态的完整注册信息。
     */
    private final Map<String, SkillRegistration> registrations = new ConcurrentHashMap<>();

    /**
     * 注册一个新的Skill到平台中。
     *
     * <p>注册流程：</p>
     * <ol>
     *   <li>从Skill元数据中提取skillId作为唯一键</li>
     *   <li>创建{@link SkillRegistration}记录，初始状态设为{@link SkillState#LOADED}</li>
     *   <li>将注册信息存入ConcurrentHashMap</li>
     *   <li>调用skill.onEnable()初始化Skill</li>
     *   <li>将状态更新为{@link SkillState#ENABLED}</li>
     * </ol>
     *
     * @param skill       要注册的Skill实例，必须提供有效的元数据
     * @param classLoader 加载该Skill所使用的类加载器，用于后续类加载隔离
     */
    public void register(Skill skill, ClassLoader classLoader) {
        String skillId = skill.metadata().skillId();
        SkillRegistration reg = new SkillRegistration(
            skillId, skill.metadata(), skill, classLoader, SkillState.LOADED);
        registrations.put(skillId, reg);
        skill.onEnable();
        reg.setState(SkillState.ENABLED);
        log.info("Registered skill: {}", skillId);
    }

    /**
     * 从注册中心注销指定的Skill。
     *
     * <p>注销时会将Skill从注册表中移除，并调用skill.onDestroy()执行清理逻辑。
     * 如果skillId不存在，此方法静默返回（幂等操作）。</p>
     *
     * @param skillId 要注销的Skill的唯一标识符
     */
    public void unregister(String skillId) {
        SkillRegistration reg = registrations.remove(skillId);
        if (reg != null) {
            reg.getSkill().onDestroy();
            log.info("Unregistered skill: {}", skillId);
        }
    }

    /**
     * 启用指定的Skill。
     *
     * <p>仅当Skill当前状态不是{@link SkillState#ENABLED}时才执行启用操作，
     * 避免重复调用onEnable()。启用后Skill变为可调用状态。</p>
     *
     * @param skillId 要启用的Skill的唯一标识符
     */
    public void enable(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        if (reg != null && reg.getState() != SkillState.ENABLED) {
            reg.getSkill().onEnable();
            reg.setState(SkillState.ENABLED);
            log.info("Enabled skill: {}", skillId);
        }
    }

    /**
     * 禁用指定的Skill。
     *
     * <p>仅当Skill当前状态为{@link SkillState#ENABLED}时才执行禁用操作。
     * 禁用后Skill仍在注册表中，但不会被查询方法返回。</p>
     *
     * @param skillId 要禁用的Skill的唯一标识符
     */
    public void disable(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        if (reg != null && reg.getState() == SkillState.ENABLED) {
            reg.getSkill().onDisable();
            reg.setState(SkillState.DISABLED);
            log.info("Disabled skill: {}", skillId);
        }
    }

    /**
     * 根据skillId获取已启用的Skill实例。
     *
     * <p>仅返回状态为{@link SkillState#ENABLED}的Skill。如果Skill不存在或已禁用，
     * 返回{@link Optional#empty()}。</p>
     *
     * @param skillId Skill的唯一标识符
     * @return 包含Skill实例的Optional，如果Skill不存在或未启用则为空
     */
    public Optional<Skill> getSkill(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        return (reg != null && reg.getState() == SkillState.ENABLED)
            ? Optional.of(reg.getSkill()) : Optional.empty();
    }

    /**
     * 根据skillId获取注册记录，不过滤状态。
     *
     * <p>运行时生命周期同步需要读取已禁用Skill的元数据，例如从ToolRouter
     * 里移除对应工具时，需要知道工具名。</p>
     *
     * @param skillId Skill的唯一标识符
     * @return 注册记录；如果Skill不存在则为空
     */
    public Optional<SkillRegistration> getRegistration(String skillId) {
        return Optional.ofNullable(registrations.get(skillId));
    }

    /**
     * 判断指定Skill实例是否仍是注册中心里的已启用实例。
     *
     * <p>此方法用于工具适配器的执行兜底：即使旧适配器没有被及时注销，
     * 只要注册中心里对应Skill已禁用或已被新实例替换，旧适配器也不能继续执行。</p>
     *
     * @param skillId Skill的唯一标识符
     * @param skill   需要校验的Skill实例
     * @return true表示该实例仍处于启用状态
     */
    public boolean isEnabled(String skillId, Skill skill) {
        SkillRegistration reg = registrations.get(skillId);
        return reg != null
            && reg.getState() == SkillState.ENABLED
            && reg.getSkill() == skill;
    }

    /**
     * 根据Skill名称查找已启用的Skill。
     *
     * <p>遍历所有已注册的Skill，匹配名称（精确匹配）且状态为{@link SkillState#ENABLED}的Skill。
     * 如果存在多个同名Skill（不同版本），返回第一个匹配的。</p>
     *
     * @param skillName Skill的显示名称（对应SkillMetadata.name()）
     * @return 包含匹配Skill实例的Optional，如果未找到则为空
     */
    public Optional<Skill> findByName(String skillName) {
        return registrations.values().stream()
            .filter(r -> r.getState() == SkillState.ENABLED)
            .filter(r -> r.getMetadata().name().equals(skillName))
            .map(SkillRegistration::getSkill)
            .findFirst();
    }

    /**
     * 列出所有已注册的Skill（包括已启用和已禁用的）。
     *
     * <p>返回的是不可变副本，不会反映后续注册操作的变化。</p>
     *
     * @return 所有SkillRegistration的不可变列表
     */
    public List<SkillRegistration> listAll() {
        return List.copyOf(registrations.values());
    }

    /**
     * 列出所有已启用的Skill。
     *
     * <p>过滤条件为状态等于{@link SkillState#ENABLED}。返回可变列表，
     * 调用方可以安全修改。</p>
     *
     * @return 已启用Skill实例的列表
     */
    public List<Skill> listEnabled() {
        return registrations.values().stream()
            .filter(r -> r.getState() == SkillState.ENABLED)
            .map(SkillRegistration::getSkill)
            .collect(Collectors.toList());
    }

    /**
     * 返回当前注册表中Skill的总数（包括所有状态）。
     *
     * @return 已注册Skill的数量
     */
    public int count() {
        return registrations.size();
    }
}
