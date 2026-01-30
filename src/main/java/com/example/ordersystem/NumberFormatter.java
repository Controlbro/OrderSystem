package com.example.ordersystem;

import java.text.DecimalFormat;

/**
 * Utility for compact number formatting.
 */
public final class NumberFormatter {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");

    private NumberFormatter() {
    }

    public static String formatCompact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) {
            return ONE_DECIMAL.format(value / 1_000_000_000D) + "B";
        }
        if (abs >= 1_000_000) {
            return ONE_DECIMAL.format(value / 1_000_000D) + "M";
        }
        if (abs >= 100_000) {
            return ONE_DECIMAL.format(value / 1_000D) + "K";
        }
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return ONE_DECIMAL.format(value);
    }

    public static String formatCompact(long value) {
        return formatCompact((double) value);
    }
}
