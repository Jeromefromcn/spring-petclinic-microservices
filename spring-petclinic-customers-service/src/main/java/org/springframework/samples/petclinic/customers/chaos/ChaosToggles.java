package org.springframework.samples.petclinic.customers.chaos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChaosToggles {

    private final Map<String, Boolean> toggles = new ConcurrentHashMap<>();

    public boolean isEnabled(String name) {
        return toggles.getOrDefault(name, false);
    }

    public void set(String name, boolean enabled) {
        toggles.put(name, enabled);
    }
}
