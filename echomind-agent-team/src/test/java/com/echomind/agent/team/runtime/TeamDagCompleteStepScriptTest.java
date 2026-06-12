package com.echomind.agent.team.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TeamDagCompleteStepScriptTest {

    @Test
    void extractsDependentStepIdBeforeAppendingNewlyReady() throws IOException {
        String script = readScript();

        assertThat(script)
            .contains("local dep_id = string.gsub(dep_key, '.*:', '')")
            .contains("table.insert(newly_ready, dep_id)")
            .doesNotContain("table.insert(newly_ready, string.gsub");
    }

    @Test
    void completionCountersAreIdempotent() throws IOException {
        String script = readScript();

        assertThat(script)
            .contains("local previous_status = redis.call('HGET', completed_step_key, 'status')")
            .contains("local first_completion = previous_status ~= 'COMPLETED'")
            .contains("if first_completion then");
    }

    @Test
    void pendingReadyAppendDeduplicatesStepIds() throws IOException {
        String script = readScript();

        assertThat(script)
            .contains("local present = {}")
            .contains("if not present[sid] then")
            .contains("present[sid] = true");
    }

    private static String readScript() throws IOException {
        return new ClassPathResource("scripts/team-dag-complete-step.lua")
            .getContentAsString(StandardCharsets.UTF_8);
    }
}
