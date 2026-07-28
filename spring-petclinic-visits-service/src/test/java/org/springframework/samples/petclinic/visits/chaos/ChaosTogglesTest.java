package org.springframework.samples.petclinic.visits.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosTogglesTest {

    @Test
    void isEnabledDefaultsToFalseForUnknownToggle() {
        ChaosToggles chaosToggles = new ChaosToggles();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isFalse();
    }

    @Test
    void isEnabledReflectsLastSetValue() {
        ChaosToggles chaosToggles = new ChaosToggles();

        chaosToggles.set("redis-timeout", true);
        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();

        chaosToggles.set("redis-timeout", false);
        assertThat(chaosToggles.isEnabled("redis-timeout")).isFalse();
    }
}
