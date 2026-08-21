package com.company.paymentanalysis.permission;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable data scope resolved from the permission database for one login. */
public record PermissionScope(
        String loginUsername,
        Map<String, List<String>> valuesByDimension) implements Serializable {

    public PermissionScope {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        valuesByDimension.forEach((dimension, values) -> copy.put(dimension, List.copyOf(values)));
        valuesByDimension = Collections.unmodifiableMap(copy);
    }
}
