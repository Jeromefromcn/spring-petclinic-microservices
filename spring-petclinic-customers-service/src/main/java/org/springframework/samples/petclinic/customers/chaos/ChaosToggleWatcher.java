package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ChaosToggleWatcher {

    private static final Logger log = LoggerFactory.getLogger(ChaosToggleWatcher.class);
    private static final String PREFIX = "chaos/customers-service/";

    private final ConsulClient consulClient;
    private final ChaosToggles chaosToggles;
    private final MeterRegistry meterRegistry;
    // Wall-clock millis of the last successful poll. Starts at construction time so a Consul
    // that never answers shows up as a growing age instead of a huge epoch-sized one.
    private final AtomicLong lastSuccessMillis;

    public ChaosToggleWatcher(ConsulClient consulClient, ChaosToggles chaosToggles, MeterRegistry meterRegistry) {
        this.consulClient = consulClient;
        this.chaosToggles = chaosToggles;
        this.meterRegistry = meterRegistry;
        this.lastSuccessMillis = new AtomicLong(meterRegistry.config().clock().wallTime());
        Gauge.builder("consul.kv.poll.last.success", lastSuccessMillis, millis -> millis.get() / 1000.0)
            .description("Unix time of the last successful Consul KV poll")
            .baseUnit("seconds")
            .register(meterRegistry);
    }

    private Timer pollTimer(String outcome) {
        return Timer.builder("consul.kv.poll")
            .description("Consul KV polls for chaos toggles, by outcome")
            .tag("outcome", outcome)
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${chaos.poll-interval-ms:5000}")
    public void poll() {
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            Response<List<GetValue>> response;
            try {
                response = consulClient.getKVValues(PREFIX);
            } catch (RuntimeException e) {
                sample.stop(pollTimer("failure"));
                throw e;
            }
            sample.stop(pollTimer("success"));
            lastSuccessMillis.set(meterRegistry.config().clock().wallTime());
            List<GetValue> values = response.getValue();
            if (values == null) {
                return;
            }
            for (GetValue value : values) {
                String name = value.getKey().substring(PREFIX.length());
                if (name.isEmpty()) {
                    continue;
                }
                chaosToggles.set(name, value.getDecodedValue());
            }
        } catch (Exception e) {
            log.warn("Failed to poll chaos toggles from Consul KV at '{}', keeping last known state", PREFIX, e);
        }
    }
}
