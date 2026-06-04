package com.echomind.console.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

class AlertRuleCacheTest {

    @Test
    void springContextInstantiatesCacheWithAutowiredConstructor() {
        new ApplicationContextRunner()
            .withBean(AlertRuleCache.class)
            .run(context -> {
                assertThat(context).hasSingleBean(AlertRuleCache.class);

                List<AlertRuleEntity> rules = context.getBean(AlertRuleCache.class)
                    .allRules(() -> List.of(rule(AlertType.SENSITIVE_DATA)));

                assertThat(rules).extracting(AlertRuleEntity::getAlertType)
                    .containsExactly(AlertType.SENSITIVE_DATA);
            });
    }

    @Test
    void returnsCachedRulesWithoutLoadingDatabase() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(AlertRuleCache.ALL_RULES_KEY))
            .thenReturn(mapper().writeValueAsString(List.of(rule(AlertType.SENSITIVE_DATA))));
        AtomicInteger loads = new AtomicInteger();
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        List<AlertRuleEntity> result = cache.allRules(() -> {
            loads.incrementAndGet();
            return List.of();
        });

        assertThat(result).extracting(AlertRuleEntity::getAlertType)
            .containsExactly(AlertType.SENSITIVE_DATA);
        assertThat(loads).hasValue(0);
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void loadsAndCachesRulesOnMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(AlertRuleCache.ALL_RULES_KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        List<AlertRuleEntity> result = cache.allRules(() -> List.of(rule(AlertType.CALL_ERROR)));

        assertThat(result).extracting(AlertRuleEntity::getAlertType).containsExactly(AlertType.CALL_ERROR);
        verify(values).set(eq(AlertRuleCache.ALL_RULES_KEY), anyString(),
            argThat(duration -> duration.getSeconds() >= 600 && duration.getSeconds() <= 720));
    }

    @Test
    void cachesEmptyRuleListToPreventPenetration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(AlertRuleCache.ALL_RULES_KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        List<AlertRuleEntity> result = cache.allRules(List::of);

        assertThat(result).isEmpty();
        verify(values).set(eq(AlertRuleCache.ALL_RULES_KEY), eq("[]"),
            argThat(duration -> duration.getSeconds() >= 30 && duration.getSeconds() <= 40));
    }

    @Test
    void cachesNullRuleToPreventPenetration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get("echomind:alert:rules:type:ERROR_RATE")).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        Optional<AlertRuleEntity> result = cache.ruleByType(AlertType.ERROR_RATE, Optional::empty);

        assertThat(result).isEmpty();
        verify(values).set(eq("echomind:alert:rules:type:ERROR_RATE"), eq("__NULL__"),
            argThat(duration -> duration.getSeconds() >= 30 && duration.getSeconds() <= 40));
    }

    @Test
    void cachedNullRuleDoesNotLoadDatabaseAgain() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get("echomind:alert:rules:type:ERROR_RATE")).thenReturn("__NULL__");
        AtomicInteger loads = new AtomicInteger();
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        Optional<AlertRuleEntity> result = cache.ruleByType(AlertType.ERROR_RATE, () -> {
            loads.incrementAndGet();
            return Optional.of(rule(AlertType.ERROR_RATE));
        });

        assertThat(result).isEmpty();
        assertThat(loads).hasValue(0);
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void fallsBackToDatabaseWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        when(values.get(AlertRuleCache.ALL_RULES_KEY)).thenThrow(new RuntimeException("redis unavailable"));
        doThrow(new RuntimeException("redis unavailable")).when(redis).delete(AlertRuleCache.ALL_RULES_KEY);
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        List<AlertRuleEntity> result = cache.allRules(() -> List.of(rule(AlertType.CALL_ERROR)));

        assertThat(result).extracting(AlertRuleEntity::getAlertType).containsExactly(AlertType.CALL_ERROR);
    }

    @Test
    void waitsAndUsesCacheWhenReloadLockIsContended() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mockValues(redis);
        AlertRuleEntity rule = rule(AlertType.CALL_ERROR);
        when(values.get(AlertRuleCache.ALL_RULES_KEY))
            .thenReturn(null)
            .thenReturn(mapper().writeValueAsString(List.of(rule)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger loads = new AtomicInteger();
        AlertRuleCache cache = new AlertRuleCache(redis, mapper());

        List<AlertRuleEntity> result = cache.allRules(() -> {
            loads.incrementAndGet();
            return List.of();
        });

        assertThat(result).extracting(AlertRuleEntity::getAlertType).containsExactly(AlertType.CALL_ERROR);
        assertThat(loads).hasValue(0);
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

    private AlertRuleEntity rule(AlertType type) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setRuleId(type.name());
        rule.setAlertType(type);
        rule.setRuleName(type.name());
        rule.setSeverity(AlertSeverity.WARNING);
        rule.setEnabled(true);
        rule.setQuietMinutes(30);
        rule.setEscalationEnabled(true);
        rule.setEscalationThreshold(3);
        return rule;
    }
}
