package com.yomahub.roguemap.memory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Metadata filter condition for search operations.
 * Supports eq, gt, gte, lt, lte, in, and between operators.
 */
public abstract class Filter {

    public abstract boolean test(String actualValue);

    public static Filter eq(String value) {
        return new EqFilter(value);
    }

    public static Filter gt(String value) {
        return new NumericFilter(value, NumericFilter.Op.GT);
    }

    public static Filter gte(String value) {
        return new NumericFilter(value, NumericFilter.Op.GTE);
    }

    public static Filter lt(String value) {
        return new NumericFilter(value, NumericFilter.Op.LT);
    }

    public static Filter lte(String value) {
        return new NumericFilter(value, NumericFilter.Op.LTE);
    }

    public static Filter in(String... values) {
        return new InFilter(values);
    }

    public static Filter between(String min, String max) {
        return new BetweenFilter(min, max);
    }

    // --- implementations ---

    private static class EqFilter extends Filter {
        private final String value;

        EqFilter(String value) {
            this.value = value;
        }

        @Override
        public boolean test(String actualValue) {
            return actualValue != null && actualValue.equals(value);
        }
    }

    private static class NumericFilter extends Filter {
        private final double ref;
        private final Op op;

        enum Op { GT, GTE, LT, LTE }

        NumericFilter(String value, Op op) {
            this.ref = Double.parseDouble(value);
            this.op = op;
        }

        @Override
        public boolean test(String actualValue) {
            if (actualValue == null) return false;
            try {
                double v = Double.parseDouble(actualValue);
                switch (op) {
                    case GT:  return v > ref;
                    case GTE: return v >= ref;
                    case LT:  return v < ref;
                    case LTE: return v <= ref;
                    default:  return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    private static class InFilter extends Filter {
        private final Set<String> values;

        InFilter(String[] values) {
            this.values = new HashSet<>(Arrays.asList(values));
        }

        @Override
        public boolean test(String actualValue) {
            return actualValue != null && values.contains(actualValue);
        }
    }

    private static class BetweenFilter extends Filter {
        private final double min;
        private final double max;

        BetweenFilter(String min, String max) {
            this.min = Double.parseDouble(min);
            this.max = Double.parseDouble(max);
        }

        @Override
        public boolean test(String actualValue) {
            if (actualValue == null) return false;
            try {
                double v = Double.parseDouble(actualValue);
                return v >= min && v <= max;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
