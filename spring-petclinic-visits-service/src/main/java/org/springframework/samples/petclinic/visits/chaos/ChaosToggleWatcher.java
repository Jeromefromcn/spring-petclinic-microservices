package org.springframework.samples.petclinic.visits.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChaosToggleWatcher {

    private static final Logger log = LoggerFactory.getLogger(ChaosToggleWatcher.class);
    private static final String PREFIX = "chaos/visits-service/";

    private final ConsulClient consulClient;
    private final ChaosToggles chaosToggles;

    public ChaosToggleWatcher(ConsulClient consulClient, ChaosToggles chaosToggles) {
        this.consulClient = consulClient;
        this.chaosToggles = chaosToggles;
    }

    @Scheduled(fixedDelayString = "${chaos.poll-interval-ms:5000}")
    public void poll() {
        try {
            Response<List<GetValue>> response = consulClient.getKVValues(PREFIX);
            List<GetValue> values = response.getValue();
            if (values == null) {
                return;
            }
            for (GetValue value : values) {
                String name = value.getKey().substring(PREFIX.length());
                if (name.isEmpty()) {
                    continue;
                }
                chaosToggles.set(name, Boolean.parseBoolean(value.getDecodedValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to poll chaos toggles from Consul KV at '{}', keeping last known state", PREFIX, e);
        }
    }
}
