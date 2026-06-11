package com.example.routesimulator.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

public final class RoutePoint {
    private final double latitude;
    private final double longitude;

    public RoutePoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("lat", latitude);
        json.put("lon", longitude);
        return json;
    }

    public static RoutePoint fromJson(JSONObject json) throws JSONException {
        return new RoutePoint(json.getDouble("lat"), json.getDouble("lon"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoutePoint)) {
            return false;
        }
        RoutePoint point = (RoutePoint) other;
        return Double.compare(point.latitude, latitude) == 0
                && Double.compare(point.longitude, longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }
}
