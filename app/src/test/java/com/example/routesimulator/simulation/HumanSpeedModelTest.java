package com.example.routesimulator.simulation;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HumanSpeedModelTest {
    @Test
    public void remainsInsideConfiguredVariationBand() {
        float baseKmh = 5.0f;
        int variationPercent = 8;
        HumanSpeedModel model = new HumanSpeedModel(baseKmh, variationPercent);
        double baseMetersPerSecond = baseKmh / 3.6;
        double minimum = baseMetersPerSecond * 0.92;
        double maximum = baseMetersPerSecond * 1.08;

        for (int i = 0; i < 2_000; i++) {
            double speed = model.nextSpeed(1.0);
            assertTrue(speed >= minimum - 0.000001);
            assertTrue(speed <= maximum + 0.000001);
        }
    }

    @Test
    public void routeFactorCanSlowDownOrSpeedUpNaturally() {
        HumanSpeedModel slowModel = new HumanSpeedModel(6.0f, 1);
        double slowSpeed = slowModel.nextSpeed(1.0, 0.5);
        assertTrue(slowSpeed < 6.0 / 3.6);

        HumanSpeedModel fastModel = new HumanSpeedModel(6.0f, 1);
        double fastSpeed = 0.0;
        for (int i = 0; i < 20; i++) {
            fastSpeed = fastModel.nextSpeed(1.0, 1.08);
        }
        assertTrue(fastSpeed > 6.0 / 3.6);
    }
}
