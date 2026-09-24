package com.antielytratarget.utils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AimStatistics {

    public static final double EXPANDER = Math.pow(2, 24);

    private AimStatistics() {}

    public static double getVariance(final Collection<? extends Number> data) {
        int count = 0;
        double sum = 0.0;
        double variance = 0.0;
        for (final Number number : data) {
            sum += number.doubleValue();
            ++count;
        }
        if (count == 0) return 0;
        final double average = sum / count;
        for (final Number number : data) {
            variance += Math.pow(number.doubleValue() - average, 2.0);
        }
        return variance / count;
    }

    public static double getMin(final Collection<? extends Number> collection) {
        double min = Double.MAX_VALUE;
        for (final Number number : collection) {
            min = Math.min(min, number.doubleValue());
        }
        return min;
    }

    public static double getMax(final Collection<? extends Number> collection) {
        double max = Double.MIN_VALUE;
        for (final Number number : collection) {
            max = Math.max(max, number.doubleValue());
        }
        return max;
    }

    public static float getGCD(double s) {
        float f1 = (float) ((float) s * 0.6 + 0.2);
        return f1 * f1 * f1 * 8.0F;
    }

    public static float getGCDValue(double s) {
        return getGCD(s) * 0.15F;
    }

    public static double getStandardDeviation(final Collection<? extends Number> data) {
        return Math.sqrt(getVariance(data));
    }

    public static double getAverage(final Collection<? extends Number> data) {
        if (data.isEmpty()) return 0;
        double sum = 0.0;
        for (final Number number : data) {
            sum += number.doubleValue();
        }
        final double result = sum / data.size();
        return Double.isNaN(result) ? 0 : result;
    }

    public static double getKurtosis(final Collection<? extends Number> data) {
        double sum = 0.0;
        int count = 0;
        for (final Number number : data) {
            sum += number.doubleValue();
            ++count;
        }
        if (count < 3.0) return 0.0;
        final double efficiencyFirst = count * (count + 1.0) / ((count - 1.0) * (count - 2.0) * (count - 3.0));
        final double efficiencySecond = 3.0 * Math.pow(count - 1.0, 2.0) / ((count - 2.0) * (count - 3.0));
        final double average = sum / count;
        double variance = 0.0;
        double varianceSquared = 0.0;
        for (final Number number : data) {
            variance += Math.pow(average - number.doubleValue(), 2.0);
            varianceSquared += Math.pow(average - number.doubleValue(), 4.0);
        }
        return efficiencyFirst * (varianceSquared / Math.pow(variance / sum, 2.0)) - efficiencySecond;
    }

    public static double getShannonEntropy(final Collection<? extends Number> data) {
        if (data.isEmpty()) return 0;
        Map<Double, Long> freqMap = data.stream()
                .collect(Collectors.groupingBy(Number::doubleValue, Collectors.counting()));
        double total = data.size();
        return -freqMap.values().stream()
                .mapToDouble(count -> (count / total) * (Math.log(count / total) / Math.log(2)))
                .sum();
    }

    public static int getDistinct(final Collection<? extends Number> data) {
        return (int) data.stream().distinct().count();
    }

    public static int getDuplicates(final Collection<? extends Number> data) {
        return data.size() - getDistinct(data);
    }

    public static Pair<List<Double>, List<Double>> getOutliers(final Collection<? extends Number> collection) {
        final List<Double> values = new ArrayList<>();
        for (final Number number : collection) {
            values.add(number.doubleValue());
        }
        if (values.size() < 2) return new Pair<>(new ArrayList<>(), new ArrayList<>());
        final double q1 = getMedian(values.subList(0, values.size() / 2));
        final double q3 = getMedian(values.subList(values.size() / 2, values.size()));
        final double iqr = Math.abs(q1 - q3);
        final double lowThreshold = q1 - 1.5 * iqr, highThreshold = q3 + 1.5 * iqr;
        final Pair<List<Double>, List<Double>> tuple = new Pair<>(new ArrayList<>(), new ArrayList<>());
        for (final Double value : values) {
            if (value < lowThreshold) {
                tuple.getX().add(value);
            } else if (value > highThreshold) {
                tuple.getY().add(value);
            }
        }
        return tuple;
    }

    public static double getMedian(final List<Double> data) {
        if (data.isEmpty()) return 0;
        if (data.size() % 2 == 0) {
            return (data.get(data.size() / 2) + data.get(data.size() / 2 - 1)) / 2;
        } else {
            return data.get(data.size() / 2);
        }
    }

    public static double getKireikoGeneric(final Collection<? extends Number> collection) {
        return (getKurtosis(collection) + (getVariance(collection) * 3.0)) / 20.0;
    }

    public static boolean isExponentiallySmall(final Number number) {
        return number.doubleValue() < 1 && (Double.toString(number.doubleValue()).contains("E") || number.doubleValue() == 0.0);
    }

    public static long getGcd(final long current, final long previous) {
        return (previous <= 16384L) ? current : getGcd(previous, current % previous);
    }

    public static double getGcdDouble(final double a, final double b) {
        if (a == b) return 0;
        if (a < b) return getGcdDouble(b, a);
        if (Math.abs(b) < 0.00001) return a;
        return getGcdDouble(b, a - Math.floor(a / b) * b);
    }

    public static double roundToPlace(double value, int places) {
        double multiplier = Math.pow(10, places);
        return Math.round(value * multiplier) / multiplier;
    }

    public static List<Double> getZScoreOutliers(final Collection<? extends Number> data, double threshold) {
        List<Double> outliers = new ArrayList<>();
        double mean = getAverage(data);
        double stdDev = getStandardDeviation(data);
        if (stdDev == 0) return outliers;
        for (Number number : data) {
            double zScore = (number.doubleValue() - mean) / stdDev;
            if (Math.abs(zScore) > threshold) {
                outliers.add(number.doubleValue());
            }
        }
        return outliers;
    }

public static BidirectionalOutlierEvidence getBidirectionalOutlierEvidence(
            final Collection<? extends Number> data,
            final double zThreshold,
            final double minAbsoluteValue,
            final double maxAbsoluteValue) {
        if (data == null || data.size() < 3) {
            return BidirectionalOutlierEvidence.NONE;
        }

        List<Double> outliers = getZScoreOutliers(data, zThreshold);
        if (outliers.size() != 2) {
            return BidirectionalOutlierEvidence.NONE;
        }

        boolean positive = false;
        boolean negative = false;
        double maxAbsoluteOutlier = 0.0;
        for (double outlier : outliers) {
            if (!Double.isFinite(outlier)) {
                return BidirectionalOutlierEvidence.NONE;
            }

            double absolute = Math.abs(outlier);
            if (absolute <= minAbsoluteValue || absolute >= maxAbsoluteValue) {
                return BidirectionalOutlierEvidence.NONE;
            }

            positive |= outlier > 0.0;
            negative |= outlier < 0.0;
            maxAbsoluteOutlier = Math.max(maxAbsoluteOutlier, absolute);
        }

        return positive && negative
                ? new BidirectionalOutlierEvidence(true, maxAbsoluteOutlier)
                : BidirectionalOutlierEvidence.NONE;
    }

    public static List<Float> getJiffDelta(List<? extends Number> data, int depth) {
        List<Float> result = new ArrayList<>();
        for (Number n : data) result.add(n.floatValue());
        for (int i = 0; i < depth; i++) {
            List<Float> calculate = new ArrayList<>();
            float old = Float.MIN_VALUE;
            for (float n : result) {
                if (old == Float.MIN_VALUE) {
                    old = n;
                    continue;
                }
                calculate.add(Math.abs(Math.abs(n) - Math.abs(old)));
                old = n;
            }
            result = new ArrayList<>(calculate);
        }
        return result;
    }

    public static double kolmogorovSmirnovTest(final List<? extends Number> data, Function<Double, Double> cdfFunction) {
        List<Double> sorted = data.stream().map(Number::doubleValue).sorted().collect(Collectors.toList());
        int n = sorted.size();
        if (n == 0) return 0;
        double dStatistic = 0;
        for (int i = 0; i < n; i++) {
            double empiricalCDF = (i + 1) / (double) n;
            double theoreticalCDF = cdfFunction.apply(sorted.get(i));
            dStatistic = Math.max(dStatistic, Math.abs(empiricalCDF - theoreticalCDF));
        }
        return dStatistic;
    }

    public static double getIQR(final Collection<? extends Number> data) {
        List<Double> sorted = data.stream().map(Number::doubleValue).sorted().collect(Collectors.toList());
        return calculatePercentile(sorted, 75) - calculatePercentile(sorted, 25);
    }

    public static double calculatePercentile(final Collection<? extends Number> data, double percentile) {
        if (data.isEmpty()) return 0;
        List<Double> sortedValues = data.stream()
                .map(Number::doubleValue)
                .sorted()
                .collect(Collectors.toList());
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sortedValues.size()) index = sortedValues.size() - 1;
        return sortedValues.get(index);
    }

public static final class Pair<X, Y> {
        private final X x;
        private final Y y;

        public Pair(X x, Y y) {
            this.x = x;
            this.y = y;
        }

        public X getX() { return x; }
        public Y getY() { return y; }
    }

    public record BidirectionalOutlierEvidence(boolean suspicious, double maxAbsoluteOutlier) {
        private static final BidirectionalOutlierEvidence NONE =
                new BidirectionalOutlierEvidence(false, 0.0);
    }

public static double scaleVal(double value, double scale) {
        double scale2 = Math.pow(10, scale);
        return Math.ceil(value * scale2) / scale2;
    }

public static double getAngleInDegrees(float deltaX, float deltaY) {
        double angleInRadians = Math.atan2(deltaX, deltaY);
        double angleInDegrees = Math.toDegrees(angleInRadians);
        if (angleInDegrees < 0) angleInDegrees += 360;
        return angleInDegrees;
    }
}
