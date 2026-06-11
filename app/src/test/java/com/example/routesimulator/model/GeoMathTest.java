package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class GeoMathTest {
    @Test
    public void samplesAlongRouteByDistance() {
        RoutePoint start = new RoutePoint(31.2304, 121.4737);
        RoutePoint middle = new RoutePoint(31.2304, 121.4837);
        RoutePoint end = new RoutePoint(31.2404, 121.4837);
        List<RoutePoint> route = Arrays.asList(start, middle, end);
        List<Double> cumulative = GeoMath.cumulativeDistances(route);

        double total = cumulative.get(cumulative.size() - 1);
        RouteSample sample = GeoMath.sampleAtDistance(route, cumulative, total / 2.0);

        assertTrue(sample.point().longitude() >= start.longitude());
        assertTrue(sample.point().longitude() <= end.longitude());
        assertTrue(sample.point().latitude() >= start.latitude());
        assertTrue(sample.point().latitude() <= end.latitude());
    }

    @Test
    public void clampsSamplesToRouteEnds() {
        RoutePoint start = new RoutePoint(0.0, 0.0);
        RoutePoint end = new RoutePoint(0.0, 0.01);
        List<RoutePoint> route = Arrays.asList(start, end);
        List<Double> cumulative = GeoMath.cumulativeDistances(route);

        RouteSample before = GeoMath.sampleAtDistance(route, cumulative, -10.0);
        RouteSample after = GeoMath.sampleAtDistance(route, cumulative, 10_000.0);

        assertEquals(start.latitude(), before.point().latitude(), 0.000001);
        assertEquals(start.longitude(), before.point().longitude(), 0.000001);
        assertEquals(end.latitude(), after.point().latitude(), 0.000001);
        assertEquals(end.longitude(), after.point().longitude(), 0.000001);
    }

    @Test
    public void simplifierKeepsEndpoints() {
        List<RoutePoint> points = Arrays.asList(
                new RoutePoint(31.0, 121.0),
                new RoutePoint(31.000001, 121.0001),
                new RoutePoint(31.0, 121.0002)
        );

        List<RoutePoint> simplified = RouteSimplifier.simplify(points, 2.0);

        assertEquals(points.get(0), simplified.get(0));
        assertEquals(points.get(points.size() - 1), simplified.get(simplified.size() - 1));
    }
}
