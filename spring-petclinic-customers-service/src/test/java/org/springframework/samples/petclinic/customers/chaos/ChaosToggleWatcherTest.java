package org.springframework.samples.petclinic.customers.chaos;

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
}
