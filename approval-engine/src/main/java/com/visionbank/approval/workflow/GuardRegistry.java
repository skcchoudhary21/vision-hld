package com.visionbank.approval.workflow;

import java.util.HashMap;
import java.util.Map;

public class GuardRegistry {
    private final Map<String, Guard> guards = new HashMap<>();

    public void register(String name, Guard guard) {
        guards.put(name, guard);
    }

    public Guard get(String name) {
        Guard guard = guards.get(name);
        if (guard == null) {
            throw new IllegalStateException("No guard registered for name: " + name);
        }
        return guard;
    }
}
