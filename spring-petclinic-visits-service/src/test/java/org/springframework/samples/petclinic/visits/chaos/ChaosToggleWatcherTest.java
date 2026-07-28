package org.springframework.samples.petclinic.visits.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChaosToggleWatcherTest {

    private final ConsulClient consulClient = mock(ConsulClient.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private final ChaosToggleWatcher watcher = new ChaosToggleWatcher(consulClient, chaosToggles);

    @Test
    void updatesTogglesFromConsulKvResponse() {
        GetValue enabled = new GetValue();
        enabled.setKey("chaos/visits-service/redis-timeout");
        enabled.setValue(Base64.getEncoder().encodeToString("true".getBytes()));

        GetValue disabled = new GetValue();
        disabled.setKey("chaos/visits-service/slow-query-enabled");
        disabled.setValue(Base64.getEncoder().encodeToString("false".getBytes()));

        given(consulClient.getKVValues("chaos/visits-service/"))
            .willReturn(new Response<>(List.of(enabled, disabled), null, null, null));

        watcher.poll();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }

    @Test
    void leavesTogglesUnchangedWhenConsulReturnsNoKeys() {
        given(consulClient.getKVValues("chaos/visits-service/"))
            .willReturn(new Response<>(null, null, null, null));

        chaosToggles.set("redis-timeout", true);
        watcher.poll();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();
    }

    @Test
    void swallowsExceptionsFromConsulClient() {
        given(consulClient.getKVValues("chaos/visits-service/"))
            .willThrow(new RuntimeException("connection refused"));

        assertThatCode(watcher::poll).doesNotThrowAnyException();
    }
}
