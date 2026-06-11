package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SavedRouteTest {
    @Test
    public void keepsDefensiveCopyOfPoints() {
        List<RoutePoint> source = new ArrayList<>(Arrays.asList(
                new RoutePoint(31.0, 121.0),
                new RoutePoint(31.001, 121.001)
        ));
        SavedRoute savedRoute = new SavedRoute("id", "路线", 1L, source);

        source.clear();

        assertEquals(2, savedRoute.points().size());
    }
}
