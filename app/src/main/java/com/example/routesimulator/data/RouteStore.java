package com.example.routesimulator.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.SavedRoute;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class RouteStore {
    private static final String PREFS = "route_simulator";
    private static final String KEY_ROUTE = "route";
    private static final String KEY_WAYPOINTS = "waypoints";
    private static final String KEY_SPEED = "speed_kmh";
    private static final String KEY_VARIATION = "variation_percent";
    private static final String KEY_RUNNING = "simulation_running";
    private static final String KEY_HEALTH_SYNC = "health_sync";
    private static final String KEY_ROUND_TRIP = "round_trip";
    private static final String KEY_SAVED_ROUTES = "saved_routes";

    private final SharedPreferences preferences;

    public RouteStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveRoute(List<RoutePoint> points) {
        savePoints(KEY_ROUTE, points);
    }

    public List<RoutePoint> loadRoute() {
        return loadPoints(KEY_ROUTE);
    }

    public void saveWaypoints(List<RoutePoint> points) {
        savePoints(KEY_WAYPOINTS, points);
    }

    public List<RoutePoint> loadWaypoints() {
        return loadPoints(KEY_WAYPOINTS);
    }

    private void savePoints(String key, List<RoutePoint> points) {
        JSONArray array = new JSONArray();
        for (RoutePoint point : points) {
            try {
                array.put(point.toJson());
            } catch (JSONException ignored) {
                // Latitude and longitude are finite values produced by the map.
            }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }

    private List<RoutePoint> loadPoints(String key) {
        List<RoutePoint> points = new ArrayList<>();
        String encoded = preferences.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                points.add(RoutePoint.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(key).apply();
        }
        return points;
    }

    public void saveSettings(float speedKmh, int variationPercent) {
        preferences.edit()
                .putFloat(KEY_SPEED, speedKmh)
                .putInt(KEY_VARIATION, variationPercent)
                .apply();
    }

    public float loadSpeedKmh() {
        return preferences.getFloat(KEY_SPEED, 5.0f);
    }

    public int loadVariationPercent() {
        return preferences.getInt(KEY_VARIATION, 6);
    }

    public void setSimulationRunning(boolean running) {
        preferences.edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public boolean isSimulationRunning() {
        return preferences.getBoolean(KEY_RUNNING, false);
    }

    public void setHealthSyncEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_HEALTH_SYNC, enabled).apply();
    }

    public boolean isHealthSyncEnabled() {
        return preferences.getBoolean(KEY_HEALTH_SYNC, false);
    }

    public void setRoundTripEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ROUND_TRIP, enabled).apply();
    }

    public boolean isRoundTripEnabled() {
        return preferences.getBoolean(KEY_ROUND_TRIP, false);
    }

    public SavedRoute saveNamedRoute(String requestedName, List<RoutePoint> points) {
        String name = requestedName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Route name cannot be empty");
        }
        if (points.size() < 2) {
            throw new IllegalArgumentException("Route must contain at least two points");
        }

        List<SavedRoute> routes = loadSavedRoutes();
        String id = UUID.randomUUID().toString();
        for (int i = 0; i < routes.size(); i++) {
            SavedRoute route = routes.get(i);
            if (route.name().equalsIgnoreCase(name)) {
                id = route.id();
                routes.remove(i);
                break;
            }
        }
        SavedRoute savedRoute = new SavedRoute(
                id,
                name,
                System.currentTimeMillis(),
                points
        );
        routes.add(savedRoute);
        writeSavedRoutes(routes);
        return savedRoute;
    }

    public List<SavedRoute> loadSavedRoutes() {
        List<SavedRoute> routes = new ArrayList<>();
        String encoded = preferences.getString(KEY_SAVED_ROUTES, "[]");
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                try {
                    routes.add(SavedRoute.fromJson(array.getJSONObject(i)));
                } catch (JSONException ignored) {
                    // Keep the remaining valid saved routes.
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_SAVED_ROUTES).apply();
        }
        routes.sort(Comparator.comparingLong(SavedRoute::updatedAtMillis).reversed());
        return routes;
    }

    public void deleteSavedRoute(String routeId) {
        List<SavedRoute> routes = loadSavedRoutes();
        routes.removeIf(route -> route.id().equals(routeId));
        writeSavedRoutes(routes);
    }

    private void writeSavedRoutes(List<SavedRoute> routes) {
        JSONArray array = new JSONArray();
        for (SavedRoute route : routes) {
            try {
                array.put(route.toJson());
            } catch (JSONException ignored) {
                // All route values are generated locally and are JSON compatible.
            }
        }
        preferences.edit().putString(KEY_SAVED_ROUTES, array.toString()).apply();
    }
}
