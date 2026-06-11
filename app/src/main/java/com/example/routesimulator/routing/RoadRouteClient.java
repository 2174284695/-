package com.example.routesimulator.routing;

import com.example.routesimulator.model.RoutePoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoadRouteClient {
    private static final int MAX_WAYPOINTS = 25;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 12_000;
    private static final int READ_TIMEOUT_MILLIS = 20_000;
    private static final String USER_AGENT =
            "RouteSimulator/1.3 (Android development and location testing)";

    public enum Profile {
        FOOT("https://routing.openstreetmap.de/routed-foot/route/v1/driving/"),
        DRIVING("https://routing.openstreetmap.de/routed-car/route/v1/driving/");

        private final String endpoint;

        Profile(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public List<RoutePoint> route(List<RoutePoint> original, Profile profile)
            throws IOException, JSONException {
        List<RoutePoint> waypoints = selectWaypoints(original, MAX_WAYPOINTS);
        if (waypoints.size() < 2) {
            throw new IllegalArgumentException("At least two route points are required");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(
                buildUrl(waypoints, profile)
        ).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("道路服务返回 HTTP " + status);
            }
            return parseRoute(readResponse(connection));
        } finally {
            connection.disconnect();
        }
    }

    static List<RoutePoint> selectWaypoints(List<RoutePoint> points, int limit) {
        if (points.size() <= limit) {
            return new ArrayList<>(points);
        }
        List<RoutePoint> selected = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int index = Math.round(i * (points.size() - 1f) / (limit - 1f));
            selected.add(points.get(index));
        }
        return selected;
    }

    static List<RoutePoint> parseRoute(String response) throws JSONException, IOException {
        JSONObject root = new JSONObject(response);
        String code = root.optString("code");
        if (!"Ok".equals(code)) {
            String message = root.optString("message", code);
            throw new IOException("道路服务无法生成路线：" + message);
        }

        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IOException("道路服务没有返回可用路线");
        }
        JSONArray coordinates = routes.getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates");
        List<RoutePoint> points = new ArrayList<>(coordinates.length());
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray coordinate = coordinates.getJSONArray(i);
            points.add(new RoutePoint(
                    coordinate.getDouble(1),
                    coordinate.getDouble(0)
            ));
        }
        if (points.size() < 2) {
            throw new IOException("道路服务返回的路线点不足");
        }
        return points;
    }

    private static String buildUrl(List<RoutePoint> points, Profile profile) {
        StringBuilder url = new StringBuilder(profile.endpoint);
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                url.append(';');
            }
            RoutePoint point = points.get(i);
            url.append(String.format(
                    Locale.US,
                    "%.6f,%.6f",
                    point.longitude(),
                    point.latitude()
            ));
        }
        return url.append("?overview=full&geometries=geojson&steps=false").toString();
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("道路服务响应过大");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
