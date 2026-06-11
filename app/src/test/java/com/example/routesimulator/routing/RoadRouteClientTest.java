package com.example.routesimulator.routing;

import com.example.routesimulator.model.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class RoadRouteClientTest {
    @Test
    public void selectWaypointsPreservesEndpointsAndLimit() {
        List<RoutePoint> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            points.add(new RoutePoint(30.0 + i / 1000.0, 120.0));
        }

        List<RoutePoint> selected = RoadRouteClient.selectWaypoints(points, 25);

        assertEquals(25, selected.size());
        assertEquals(points.get(0), selected.get(0));
        assertEquals(points.get(99), selected.get(24));
    }

}
