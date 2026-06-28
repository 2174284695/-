package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class RouteSegmentClassifierTest {
    @Test
    public void forcedWaypointMarksAdjacentSegmentsAsOffRoad() {
        RoutePoint forced = new RoutePoint(31.000100, 121.000100);
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                forced,
                new RoutePoint(31.000200, 121.000000)
        );

        boolean[] segments = RouteSegmentClassifier.offRoadSegmentsForForcedWaypoints(
                route,
                List.of(RouteWaypoint.forced(forced))
        );

        assertEquals(2, segments.length);
        assertTrue(segments[0]);
        assertTrue(segments[1]);
    }

    @Test
    public void roadWaypointDoesNotMarkOffRoadSegments() {
        RoutePoint road = new RoutePoint(31.000100, 121.000100);
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                road,
                new RoutePoint(31.000200, 121.000000)
        );

        boolean[] segments = RouteSegmentClassifier.offRoadSegmentsForForcedWaypoints(
                route,
                List.of(RouteWaypoint.road(road))
        );

        assertFalse(segments[0]);
        assertFalse(segments[1]);
    }

    @Test
    public void speedFactorDropsOnOffRoadSegmentAndApproach() {
        List<RoutePoint> route = List.of(
                new RoutePoint(31.000000, 121.000000),
                new RoutePoint(31.000090, 121.000000),
                new RoutePoint(31.000180, 121.000000)
        );
        List<Double> cumulative = GeoMath.cumulativeDistances(route);
        boolean[] segments = new boolean[]{false, true};

        assertEquals(
                1.0,
                RouteSegmentClassifier.speedFactorForProgress(1.0, cumulative, segments),
                0.000001
        );
        assertTrue(RouteSegmentClassifier.speedFactorForProgress(
                cumulative.get(1) - 1.0,
                cumulative,
                segments
        ) < 1.0);
        assertEquals(
                0.72,
                RouteSegmentClassifier.speedFactorForProgress(
                        cumulative.get(1) + 1.0,
                        cumulative,
                        segments
                ),
                0.000001
        );
    }
}
