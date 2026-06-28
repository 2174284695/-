package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class RouteDraftMergerTest {
    @Test
    public void appendsStrokeFromCurrentEnd() {
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                new RoutePoint(31.000100, 121.000000)
        );
        RoutePoint newEnd = new RoutePoint(31.000200, 121.000000);
        List<RoutePoint> stroke = List.of(route.get(1), newEnd);

        List<RoutePoint> merged = RouteDraftMerger.merge(route, stroke, 1.0);

        assertEquals(List.of(route.get(0), route.get(1), newEnd), merged);
    }

    @Test
    public void prependsStrokeThatEndsAtCurrentStart() {
        RoutePoint newStart = new RoutePoint(30.999900, 121.000000);
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                new RoutePoint(31.000100, 121.000000)
        );
        List<RoutePoint> stroke = List.of(newStart, route.get(0));

        List<RoutePoint> merged = RouteDraftMerger.merge(route, stroke, 1.0);

        assertEquals(List.of(newStart, route.get(0), route.get(1)), merged);
    }

    @Test
    public void prependsStrokeDrawnOutwardFromCurrentStart() {
        RoutePoint newStart = new RoutePoint(30.999900, 121.000000);
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                new RoutePoint(31.000100, 121.000000)
        );
        List<RoutePoint> stroke = List.of(route.get(0), newStart);

        List<RoutePoint> merged = RouteDraftMerger.merge(route, stroke, 1.0);

        assertEquals(List.of(newStart, route.get(0), route.get(1)), merged);
    }
}
