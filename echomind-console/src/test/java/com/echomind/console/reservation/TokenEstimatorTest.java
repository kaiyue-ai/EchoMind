package com.echomind.console.reservation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void nullMessageReturnsMin() {
        assertThat(TokenEstimator.estimate(null)).isEqualTo(512);
    }

    @Test
    void emptyMessageReturnsMin() {
        assertThat(TokenEstimator.estimate("")).isEqualTo(512);
    }

    @Test
    void blankMessageReturnsMin() {
        assertThat(TokenEstimator.estimate("   ")).isEqualTo(512);
    }

    @Test
    void shortMessageClampedToMin() {
        assertThat(TokenEstimator.estimate("你好")).isEqualTo(512);
    }

    @Test
    void mediumMessageClampedToMin() {
        String msg = "a".repeat(100);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(512);
    }

    @Test
    void message500Chars() {
        // 500 / 2.5 * 2 * 1.2 = 480 → clamped to 512
        String msg = "a".repeat(500);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(512);
    }

    @Test
    void message1000Chars() {
        // 1000 / 2.5 * 2 * 1.2 = 960
        String msg = "a".repeat(1000);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(960);
    }

    @Test
    void message2000Chars() {
        // 2000 / 2.5 * 2 * 1.2 = 1920
        String msg = "a".repeat(2000);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(1920);
    }

    @Test
    void message3000Chars() {
        // 3000 / 2.5 * 2 * 1.2 = 2880
        String msg = "a".repeat(3000);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(2880);
    }

    @Test
    void longMessageHitsMax() {
        // 5000 / 2.5 * 2 * 1.2 = 4800 → clamped to 4096
        String msg = "a".repeat(5000);
        assertThat(TokenEstimator.estimate(msg)).isEqualTo(4096);
    }

    @Test
    void processedTokensForEmptyPromptUsesMaxOutputOnly() {
        assertThat(TokenEstimator.estimateProcessedTokens("", 4096)).isEqualTo(4096);
    }

    @Test
    void processedTokensAddsEstimatedInputToMaxOutput() {
        String msg = "a".repeat(1000);

        assertThat(TokenEstimator.estimateProcessedTokens(msg, 4096)).isEqualTo(4496);
    }

    @Test
    void processedTokensSupportsDifferentMaxOutputValues() {
        String msg = "a".repeat(250);

        assertThat(TokenEstimator.estimateProcessedTokens(msg, 1024)).isEqualTo(1124);
    }

    @Test
    void processedTokensTreatsNegativeMaxOutputAsZero() {
        String msg = "a".repeat(25);

        assertThat(TokenEstimator.estimateProcessedTokens(msg, -1)).isEqualTo(10);
    }
}
