package org.dar316.spring_ai.util;

public final class StringUtils {

    private StringUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String valueAsString(Object value) {
        return value == null ? null : value.toString();
    }
}
