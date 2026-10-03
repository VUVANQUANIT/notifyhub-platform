package com.vuvanquan.notifyhub.notification.worker;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class DeliveryPolicyTest {
    @Test void retries_use_exponential_backoff_and_stop_at_the_attempt_limit() {
        var policy = new DeliveryPolicy(4, Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertThat(policy.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.retryDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.retryDelay(3)).isEqualTo(Duration.ofSeconds(3));
        assertThat(policy.shouldRetry(3, true)).isTrue();
        assertThat(policy.shouldRetry(4, true)).isFalse();
        assertThat(policy.shouldRetry(1, false)).isFalse();
    }

    @Test void one_attempt_disables_delivery_retries() {
        var policy = new DeliveryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(60));
        assertThat(policy.shouldRetry(1, true)).isFalse();
    }

    @Test void rejects_unbounded_or_invalid_retry_configuration() {
        assertThatThrownBy(() -> new DeliveryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryPolicy(11, Duration.ofSeconds(1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryPolicy(4, Duration.ZERO, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryPolicy(4, Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
