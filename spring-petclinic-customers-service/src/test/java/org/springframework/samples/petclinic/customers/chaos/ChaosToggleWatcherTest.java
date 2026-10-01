package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChaosToggleWatcherTest {

    private final ConsulClient consulClient = mock(ConsulClient.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ChaosToggleWatcher watcher = new ChaosToggleWatcher(consulClient, chaosToggles, meterRegistry);

    @Test
    void updatesTogglesFromConsulKvResponse() {
        GetValue enabled = new GetValue();
        enabled.setKey("chaos/customers-service/slow-query-enabled");
        enabled.setValue(Base64.getEncoder().encodeToString("true".getBytes()));

        GetValue disabled = new GetValue();
        disabled.setKey("chaos/customers-service/downstream-error");
        disabled.setValue(Base64.getEncoder().encodeToString("false".getBytes()));

        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(List.of(enabled, disabled), null, null, null));

        watcher.poll();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();
        assertThat(chaosToggles.isEnabled("downstream-error")).isFalse();
    }

    @Test
    void leavesTogglesUnchangedWhenConsulReturnsNoKeys() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(null, null, null, null));

        chaosToggles.set("slow-query-enabled", true);
        watcher.poll();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();
    }

    @Test
    void swallowsExceptionsFromConsulClient() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willThrow(new RuntimeException("connection refused"));

        assertThatCode(watcher::poll).doesNotThrowAnyException();
    }

    @Test
    void recordsSuccessfulPollAndAdvancesLastSuccessTime() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(List.of(), null, null, null));
        double startedAt = lastSuccessSeconds();
        clock.add(10, TimeUnit.SECONDS);

        watcher.poll();

        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(pollCount("failure")).isZero();
        assertThat(lastSuccessSeconds()).isCloseTo(startedAt + 10.0, within(0.0001));
    }

    @Test
    void countsNoKeysAsSuccessfulPoll() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(null, null, null, null));

        watcher.poll();

        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(pollCount("failure")).isZero();
    }

    @Test
    void recordsFailedPollAndKeepsLastSuccessTime() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(List.of(), null, null, null))
            .willThrow(new RuntimeException("connection refused"));

        clock.add(5, TimeUnit.SECONDS);
        watcher.poll();
        double afterSuccess = lastSuccessSeconds();
        clock.add(30, TimeUnit.SECONDS);
        watcher.poll();

        assertThat(pollCount("success")).isEqualTo(1);
        assertThat(pollCount("failure")).isEqualTo(1);
        assertThat(lastSuccessSeconds()).isEqualTo(afterSuccess);
    }

    private long pollCount(String outcome) {
        Timer timer = meterRegistry.find("consul.kv.poll").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double lastSuccessSeconds() {
        return meterRegistry.get("consul.kv.poll.last.success").gauge().value();
    }
}
