package com.example.routesimulator.simulation;

import java.util.Random;

public final class HumanSpeedModel {
    private final double baseSpeedMetersPerSecond;
    private final double variationFraction;
    private final Random random = new Random();

    private double drift;
    private double currentSpeed;

    public HumanSpeedModel(float speedKmh, int variationPercent) {
        baseSpeedMetersPerSecond = Math.max(0.1, speedKmh / 3.6);
        variationFraction = Math.max(0.0, Math.min(0.20, variationPercent / 100.0));
        currentSpeed = baseSpeedMetersPerSecond;
    }

    public double nextSpeed(double deltaSeconds) {
        return nextSpeed(deltaSeconds, 1.0);
    }

    public double nextSpeed(double deltaSeconds, double routeFactor) {
        double dt = Math.max(0.05, Math.min(2.0, deltaSeconds));
        double factor = clamp(routeFactor, 0.35, 1.18);

        // Mean-reverting random drift gives gradual, natural-looking variation.
        double reversion = 0.45;
        double noise = variationFraction * 0.32;
        drift += -reversion * drift * dt + noise * Math.sqrt(dt) * random.nextGaussian();
        drift = clamp(drift, -variationFraction, variationFraction);

        double desired = baseSpeedMetersPerSecond * (1.0 + drift) * factor;
        double maxAcceleration = Math.max(0.18, baseSpeedMetersPerSecond * 0.12);
        double maximumChange = maxAcceleration * dt;
        currentSpeed += clamp(desired - currentSpeed, -maximumChange, maximumChange);

        double minimum = baseSpeedMetersPerSecond * factor * (1.0 - variationFraction);
        double maximum = baseSpeedMetersPerSecond * factor * (1.0 + variationFraction);
        return clamp(currentSpeed, minimum, maximum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
