package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
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

    @Test
    public void keepsDefensiveCopyOfWaypoints() {
        List<RouteWaypoint> source = new ArrayList<>(Arrays.asList(
                RouteWaypoint.road(new RoutePoint(31.0, 121.0)),
                RouteWaypoint.forced(new RoutePoint(31.001, 121.001))
        ));
        SavedRoute savedRoute = new SavedRoute(
                "id",
                "路线",
                1L,
                Arrays.asList(
                        new RoutePoint(31.0, 121.0),
                        new RoutePoint(31.001, 121.001)
                ),
                source
        );

        source.clear();

        assertEquals(2, savedRoute.waypoints().size());
    }

    @Test
    public void waypointMetadataRoundTripsThroughJson() throws Exception {
        SavedRoute savedRoute = new SavedRoute(
                "id",
                "路线",
                1L,
                Arrays.asList(
                        new RoutePoint(31.0, 121.0),
                        new RoutePoint(31.001, 121.001)
                ),
                Arrays.asList(
                        RouteWaypoint.road(new RoutePoint(31.0, 121.0)),
                        RouteWaypoint.forced(new RoutePoint(31.001, 121.001))
                )
        );

        SavedRoute restored = SavedRoute.fromJson(savedRoute.toJson());

        assertEquals(savedRoute.points(), restored.points());
        assertEquals(savedRoute.waypoints(), restored.waypoints());
    }

    @Test
    public void oldSavedRouteJsonWithoutWaypointsStillLoads() throws Exception {
        JSONArray points = new JSONArray()
                .put(new RoutePoint(31.0, 121.0).toJson())
                .put(new RoutePoint(31.001, 121.001).toJson());
        JSONObject json = new JSONObject()
                .put("id", "id")
                .put("name", "旧路线")
                .put("updatedAt", 1L)
                .put("points", points);

        SavedRoute restored = SavedRoute.fromJson(json);

        assertEquals(2, restored.points().size());
        assertTrue(restored.waypoints().isEmpty());
    }
}
