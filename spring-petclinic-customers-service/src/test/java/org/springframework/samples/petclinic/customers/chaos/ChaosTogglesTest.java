package org.springframework.samples.petclinic.customers.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosTogglesTest {

    @Test
    void isEnabledDefaultsToFalseForUnknownToggle() {
        ChaosToggles chaosToggles = new ChaosToggles();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }

    @Test
    void isEnabledReflectsLastSetValue() {
        ChaosToggles chaosToggles = new ChaosToggles();

        chaosToggles.set("slow-query-enabled", true);
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();

        chaosToggles.set("slow-query-enabled", false);
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }
}
