package com.example.routesimulator.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SimulatedStepModelTest {
    @Test
    public void accumulatesFractionalStepsAcrossTicks() {
        SimulatedStepModel model = new SimulatedStepModel();

        for (int i = 0; i < 10; i++) {
            model.advance(0.35, 1.4);
        }

        assertTrue(model.totalSteps() >= 4L);
        assertTrue(model.totalSteps() <= 5L);
    }

    @Test
    public void longerDistanceProducesMoreSteps() {
        SimulatedStepModel model = new SimulatedStepModel();
        long first = model.advance(10.0, 1.0);
        long second = model.advance(20.0, 1.0);

        assertTrue(first > 0L);
        assertTrue(second > first);
    }

    @Test
    public void zeroDistanceDoesNotAddSteps() {
        SimulatedStepModel model = new SimulatedStepModel();
        assertEquals(0L, model.advance(0.0, 1.4));
        assertEquals(0L, model.totalSteps());
    }
}
