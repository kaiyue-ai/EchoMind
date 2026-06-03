package com.echomind.console.sensitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SensitiveRuleCacheTest {

    @Test
    void springContextInstantiatesCacheWithAutowiredConstructor() {
        new ApplicationContextRunner()
            .withBean(SensitiveRuleCache.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SensitiveRuleCache.class);

                List<SensitiveRuleEntity> rules = context.getBean(SensitiveRuleCache.class)
                    .enabledRules(() -> List.of(rule("email", "邮箱")));

                assertThat(rules).extracting(SensitiveRuleEntity::getRuleId).containsExactly("email");
            });
    }

    @Test
    void returnsCachedEnabledRulesWithoutLoadingDatabase() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        SensitiveRuleEntity rule = rule("email", "邮箱");
        when(values.get(SensitiveRuleCache.ENABLED_RULES_KEY)).thenReturn(mapper().writeValueAsString(List.of(rule)));
        AtomicInteger loads = new AtomicInteger();
        SensitiveRuleCache cache = new SensitiveRuleCache(redis, mapper());

        List<SensitiveRuleEntity> result = cache.enabledRules(() -> {
            loads.incrementAndGet();
            return List.of();
        });

        assertThat(result).extracting(SensitiveRuleEntity::getRuleId).containsExactly("email");
        assertThat(loads).hasValue(0);
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void loadsAndCachesRulesOnMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(SensitiveRuleCache.ENABLED_RULES_KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        SensitiveRuleCache cache = new SensitiveRuleCache(redis, mapper());

        List<SensitiveRuleEntity> result = cache.enabledRules(() -> List.of(rule("phone", "手机号")));

        assertThat(result).extracting(SensitiveRuleEntity::getRuleId).containsExactly("phone");
        verify(values).set(eq(SensitiveRuleCache.ENABLED_RULES_KEY), anyString(),
            argThat(duration -> duration.getSeconds() >= 600 && duration.getSeconds() <= 720));
    }

    @Test
    void cachesEmptyRuleListToPreventPenetration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(SensitiveRuleCache.ALL_RULES_KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        SensitiveRuleCache cache = new SensitiveRuleCache(redis, mapper());

        List<SensitiveRuleEntity> result = cache.allRules(List::of);

        assertThat(result).isEmpty();
        verify(values).set(eq(SensitiveRuleCache.ALL_RULES_KEY), eq("[]"),
            argThat(duration -> duration.getSeconds() >= 30 && duration.getSeconds() <= 40));
    }

    @Test
    void cachedEmptyRuleListDoesNotLoadDatabaseAgain() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(SensitiveRuleCache.ALL_RULES_KEY)).thenReturn("[]");
        AtomicInteger loads = new AtomicInteger();
        SensitiveRuleCache cache = new SensitiveRuleCache(redis, mapper());

        List<SensitiveRuleEntity> result = cache.allRules(() -> {
            loads.incrementAndGet();
            return List.of(rule("email", "邮箱"));
        });

        assertThat(result).isEmpty();
        assertThat(loads).hasValue(0);
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void fallsBackToDatabaseWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(SensitiveRuleCache.ENABLED_RULES_KEY)).thenThrow(new RuntimeException("redis unavailable"));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RuntimeException("redis unavailable"));
        doThrow(new RuntimeException("redis unavailable"))
            .when(values)
            .set(eq(SensitiveRuleCache.ENABLED_RULES_KEY), anyString(), any(Duration.class));
        SensitiveRuleCache cache = new SensitiveRuleCache(redis, mapper());

        List<SensitiveRuleEntity> result = cache.enabledRules(() -> List.of(rule("email", "邮箱")));

        assertThat(result).extracting(SensitiveRuleEntity::getRuleId).containsExactly("email");
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> mockValues(StringRedisTemplate redis) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return values;
    }

    private ObjectMapper mapper() {
        return new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private SensitiveRuleEntity rule(String id, String name) {
        SensitiveRuleEntity rule = new SensitiveRuleEntity();
        rule.setRuleId(id);
        rule.setRuleName(name);
        rule.setPattern(".*");
        rule.setReplacement("[" + name + "]");
        rule.setAction(SensitiveAction.MASK);
        rule.setEnabled(true);
        return rule;
    }
}
