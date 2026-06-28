package com.example.routesimulator.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class RouteWaypointTest {
    @Test
    public void oldPointJsonLoadsAsAutomaticRoadWaypoint() throws Exception {
        JSONObject json = new JSONObject()
                .put("lat", 31.230400)
                .put("lon", 121.473700);

        RouteWaypoint waypoint = RouteWaypoint.fromJson(json);

        assertEquals(new RoutePoint(31.230400, 121.473700), waypoint.point());
        assertEquals(WaypointType.ROAD, waypoint.type());
        assertFalse(waypoint.isManualType());
    }

    @Test
    public void forcedWaypointRoundTripsThroughJson() throws Exception {
        RouteWaypoint original = RouteWaypoint.forced(
                new RoutePoint(31.230500, 121.474200)
        );

        RouteWaypoint restored = RouteWaypoint.fromJson(original.toJson());

        assertEquals(original, restored);
        assertEquals(WaypointType.FORCED, restored.type());
        assertTrue(restored.isManualType());
    }

    @Test
    public void resolvedTypeKeepsAutomaticFlag() {
        RouteWaypoint waypoint = RouteWaypoint.auto(
                new RoutePoint(31.230500, 121.474200)
        ).withResolvedType(WaypointType.FORCED);

        assertEquals(WaypointType.FORCED, waypoint.type());
        assertFalse(waypoint.isManualType());
    }
}
