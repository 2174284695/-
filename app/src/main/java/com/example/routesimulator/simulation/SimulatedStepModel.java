package com.example.routesimulator.simulation;

public final class SimulatedStepModel {
    private double exactSteps;
    private double currentCadenceStepsPerMinute;

    public long advance(double distanceMeters, double speedMetersPerSecond) {
        if (distanceMeters <= 0.0 || speedMetersPerSecond <= 0.0) {
            currentCadenceStepsPerMinute = 0.0;
            return 0L;
        }
        long previousWholeSteps = (long) Math.floor(exactSteps);
        double strideMeters = estimateStrideMeters(speedMetersPerSecond);
        exactSteps += distanceMeters / strideMeters;
        currentCadenceStepsPerMinute = speedMetersPerSecond / strideMeters * 60.0;
        return (long) Math.floor(exactSteps) - previousWholeSteps;
    }

    public long totalSteps() {
        return (long) Math.floor(exactSteps);
    }

    public double cadenceStepsPerMinute() {
        return currentCadenceStepsPerMinute;
    }

    public static double estimateStrideMeters(double speedMetersPerSecond) {
        // Slow walking shortens the stride; brisk walking lengthens it.
        return clamp(0.48 + speedMetersPerSecond * 0.18, 0.54, 0.92);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
