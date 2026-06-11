package com.example.routesimulator.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SavedRoute {
    private final String id;
    private final String name;
    private final long updatedAtMillis;
    private final List<RoutePoint> points;

    public SavedRoute(
            String id,
            String name,
            long updatedAtMillis,
            List<RoutePoint> points
    ) {
        this.id = id;
        this.name = name;
        this.updatedAtMillis = updatedAtMillis;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long updatedAtMillis() {
        return updatedAtMillis;
    }

    public List<RoutePoint> points() {
        return points;
    }

    public double distanceMeters() {
        return GeoMath.totalDistanceMeters(points);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("updatedAt", updatedAtMillis);
        JSONArray encodedPoints = new JSONArray();
        for (RoutePoint point : points) {
            encodedPoints.put(point.toJson());
        }
        json.put("points", encodedPoints);
        return json;
    }

    public static SavedRoute fromJson(JSONObject json) throws JSONException {
        JSONArray encodedPoints = json.getJSONArray("points");
        List<RoutePoint> points = new ArrayList<>(encodedPoints.length());
        for (int i = 0; i < encodedPoints.length(); i++) {
            points.add(RoutePoint.fromJson(encodedPoints.getJSONObject(i)));
        }
        return new SavedRoute(
                json.getString("id"),
                json.getString("name"),
                json.getLong("updatedAt"),
                points
        );
    }
}
