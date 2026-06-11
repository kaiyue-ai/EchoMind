package com.echomind.agent.team.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnClass(StringRedisTemplate.class)
public class TeamRedisConfig {

    @Bean
    public StringRedisTemplate teamStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean("teamCompleteStepScript")
    public DefaultRedisScript<List> teamCompleteStepScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/team-dag-complete-step.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean("teamClaimSlotScript")
    public DefaultRedisScript<String> teamClaimSlotScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/team-dag-claim-slot.lua"));
        script.setResultType(String.class);
        return script;
    }

    @Bean("teamReleaseSlotScript")
    public DefaultRedisScript<Long> teamReleaseSlotScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/team-dag-release-slot.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean("teamMarkReadyScript")
    public DefaultRedisScript<Long> teamMarkReadyScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/team-dag-mark-ready.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean("teamSetControlFlagScript")
    public DefaultRedisScript<Long> teamSetControlFlagScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/team-dag-set-control.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
