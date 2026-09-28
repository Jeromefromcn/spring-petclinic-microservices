package org.springframework.samples.petclinic.customers.chaos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChaosToggles {

    // Raw values from Consul: booleans for the on/off toggles, a pod name for
    // fail-instance.
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public boolean isEnabled(String name) {
        return Boolean.parseBoolean(values.get(name));
    }

    public String value(String name) {
        return values.getOrDefault(name, "");
    }

    public void set(String name, boolean enabled) {
        set(name, Boolean.toString(enabled));
    }

    public void set(String name, String value) {
        values.put(name, value == null ? "" : value);
    }
}
