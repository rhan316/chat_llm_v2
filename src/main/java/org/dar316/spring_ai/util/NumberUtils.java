package org.dar316.spring_ai.util;

public final class NumberUtils {

    private NumberUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Double valueAsDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        if (value == null) {
            return null;
        }

        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer valueAsInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int normalize(
            Integer value,
            int defaultValue,
            int min,
            int max
    ) {
        int normalized = value == null ? defaultValue : value;

        return Math.max(min, Math.min(normalized, max));
    }
}
