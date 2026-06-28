package com.example.routesimulator.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RouteWaypoint {
    private static final String KEY_TYPE = "type";
    private static final String KEY_MANUAL_TYPE = "manualType";

    private final RoutePoint point;
    private final WaypointType type;
    private final boolean manualType;

    private RouteWaypoint(RoutePoint point, WaypointType type, boolean manualType) {
        this.point = point;
        this.type = type;
        this.manualType = manualType;
    }

    public static RouteWaypoint auto(RoutePoint point) {
        return new RouteWaypoint(point, WaypointType.ROAD, false);
    }

    public static RouteWaypoint road(RoutePoint point) {
        return new RouteWaypoint(point, WaypointType.ROAD, true);
    }

    public static RouteWaypoint forced(RoutePoint point) {
        return new RouteWaypoint(point, WaypointType.FORCED, true);
    }

    public RoutePoint point() {
        return point;
    }

    public WaypointType type() {
        return type;
    }

    public boolean isManualType() {
        return manualType;
    }

    public RouteWaypoint withPoint(RoutePoint newPoint) {
        return new RouteWaypoint(newPoint, type, manualType);
    }

    public RouteWaypoint withResolvedType(WaypointType resolvedType) {
        return new RouteWaypoint(point, resolvedType, manualType);
    }

    public RouteWaypoint withManualType(WaypointType newType) {
        return new RouteWaypoint(point, newType, true);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = point.toJson();
        json.put(KEY_TYPE, type.name());
        json.put(KEY_MANUAL_TYPE, manualType);
        return json;
    }

    public static RouteWaypoint fromJson(JSONObject json) throws JSONException {
        RoutePoint point = RoutePoint.fromJson(json);
        if (!json.has(KEY_TYPE)) {
            return auto(point);
        }
        WaypointType type = WaypointType.valueOf(json.getString(KEY_TYPE));
        boolean manualType = json.optBoolean(KEY_MANUAL_TYPE, true);
        return new RouteWaypoint(point, type, manualType);
    }

    public static List<RoutePoint> pointsOf(List<RouteWaypoint> waypoints) {
        List<RoutePoint> points = new ArrayList<>(waypoints.size());
        for (RouteWaypoint waypoint : waypoints) {
            points.add(waypoint.point());
        }
        return points;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RouteWaypoint)) {
            return false;
        }
        RouteWaypoint waypoint = (RouteWaypoint) other;
        return manualType == waypoint.manualType
                && point.equals(waypoint.point)
                && type == waypoint.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(point, type, manualType);
    }
}
