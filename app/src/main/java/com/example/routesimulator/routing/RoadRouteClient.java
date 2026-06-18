package com.example.routesimulator.routing;

import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.RouteSimplifier;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoadRouteClient {
    private static final String TAG = "RoadRouteClient";
    private static final int MAX_WAYPOINTS = 25;
    private static final int OSRM_BATCH_SIZE = 8;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 20_000;
    private static final String VALHALLA_ENDPOINT =
            "https://valhalla1.openstreetmap.de/route";
    private static final String USER_AGENT =
            "RouteSimulator/1.7 (Android development and location testing)";

    public enum Profile {
        FOOT(
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/",
                "pedestrian"
        );

        private final String osrmEndpoint;
        private final String valhallaCosting;

        Profile(String osrmEndpoint, String valhallaCosting) {
            this.osrmEndpoint = osrmEndpoint;
            this.valhallaCosting = valhallaCosting;
        }
    }

    public List<RoutePoint> route(List<RoutePoint> original, Profile profile)
            throws IOException {
        List<RoutePoint> waypoints = selectWaypoints(original, MAX_WAYPOINTS);
        if (waypoints.size() < 2) {
            throw new IllegalArgumentException("至少需要两个路线点");
        }

        String osrmError;
        try {
            Log.i(TAG, "routing with OSRM, profile=" + profile
                    + ", waypoints=" + waypoints.size());
            return requestOsrmInBatches(waypoints, profile);
        } catch (Exception exception) {
            osrmError = describe(exception);
            Log.w(TAG, "OSRM failed: " + osrmError, exception);
        }

        try {
            Log.i(TAG, "routing with Valhalla fallback, profile=" + profile
                    + ", waypoints=" + waypoints.size());
            return requestValhalla(waypoints, profile);
        } catch (Exception exception) {
            Log.e(TAG, "Valhalla failed: " + describe(exception), exception);
            throw new IOException(
                    "主服务失败：" + osrmError
                            + "\n备用服务失败：" + describe(exception),
                    exception
            );
        }
    }

    static List<RoutePoint> selectWaypoints(List<RoutePoint> points, int limit) {
        if (points.size() <= limit) {
            return new ArrayList<>(points);
        }
        double toleranceMeters = 1.5;
        List<RoutePoint> simplified = new ArrayList<>(points);
        while (simplified.size() > limit && toleranceMeters <= 100.0) {
            simplified = RouteSimplifier.simplify(points, toleranceMeters);
            toleranceMeters *= 1.7;
        }
        if (simplified.size() <= limit) {
            return simplified;
        }

        List<RoutePoint> selected = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int index = Math.round(i * (simplified.size() - 1f) / (limit - 1f));
            selected.add(simplified.get(index));
        }
        return selected;
    }

    static List<RoutePoint> decodePolyline6(String encoded) throws IOException {
        List<RoutePoint> points = new ArrayList<>();
        int index = 0;
        long latitude = 0;
        long longitude = 0;
        while (index < encoded.length()) {
            long[] latitudeValue = decodeValue(encoded, index);
            latitude += latitudeValue[0];
            index = (int) latitudeValue[1];
            if (index >= encoded.length()) {
                throw new IOException("备用服务返回的路线编码不完整");
            }
            long[] longitudeValue = decodeValue(encoded, index);
            longitude += longitudeValue[0];
            index = (int) longitudeValue[1];
            points.add(new RoutePoint(latitude / 1_000_000.0, longitude / 1_000_000.0));
        }
        return points;
    }

    private List<RoutePoint> requestOsrmInBatches(
            List<RoutePoint> waypoints,
            Profile profile
    ) throws IOException, JSONException {
        List<RoutePoint> result = new ArrayList<>();
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + OSRM_BATCH_SIZE, waypoints.size());
            Log.i(TAG, "OSRM batch " + start + ".." + (end - 1));
            appendWithoutDuplicate(
                    result,
                    requestOsrm(waypoints.subList(start, end), profile)
            );
            if (end == waypoints.size()) {
                break;
            }
            start = end - 1;
        }
        if (result.size() < 2) {
            throw new IOException("主服务返回的道路点不足");
        }
        return result;
    }

    private List<RoutePoint> requestOsrm(
            List<RoutePoint> waypoints,
            Profile profile
    ) throws IOException, JSONException {
        HttpURLConnection connection = openConnection(
                new URL(buildOsrmUrl(waypoints, profile))
        );
        connection.setRequestMethod("GET");
        try {
            requireSuccess(connection, "主服务");
            List<RoutePoint> result = parseOsrm(readResponse(connection.getInputStream()));
            Log.i(TAG, "OSRM returned " + result.size() + " points");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private List<RoutePoint> requestValhalla(
            List<RoutePoint> waypoints,
            Profile profile
    ) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        JSONArray locations = new JSONArray();
        for (RoutePoint point : waypoints) {
            JSONObject location = new JSONObject();
            location.put("lat", point.latitude());
            location.put("lon", point.longitude());
            locations.put(location);
        }
        request.put("locations", locations);
        request.put("costing", profile.valhallaCosting);
        request.put("units", "kilometers");

        HttpURLConnection connection = openConnection(new URL(VALHALLA_ENDPOINT));
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        try {
            requireSuccess(connection, "备用服务");
            JSONObject root = new JSONObject(readResponse(connection.getInputStream()));
            JSONObject trip = root.getJSONObject("trip");
            JSONArray legs = trip.getJSONArray("legs");
            List<RoutePoint> result = new ArrayList<>();
            for (int i = 0; i < legs.length(); i++) {
                appendWithoutDuplicate(
                        result,
                        decodePolyline6(legs.getJSONObject(i).getString("shape"))
                );
            }
            if (result.size() < 2) {
                throw new IOException("备用服务返回的道路点不足");
            }
            Log.i(TAG, "Valhalla returned " + result.size() + " points");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static List<RoutePoint> parseOsrm(String response)
            throws JSONException, IOException {
        JSONObject root = new JSONObject(response);
        String code = root.optString("code");
        if (!"Ok".equals(code)) {
            throw new IOException(root.optString("message", code));
        }

        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IOException("没有返回可用路线");
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
        return points;
    }

    private static long[] decodeValue(String encoded, int start) throws IOException {
        long result = 0;
        int shift = 0;
        int index = start;
        int value;
        do {
            if (index >= encoded.length() || shift > 60) {
                throw new IOException("备用服务返回的路线编码无效");
            }
            value = encoded.charAt(index++) - 63;
            result |= (long) (value & 0x1f) << shift;
            shift += 5;
        } while (value >= 0x20);
        long delta = (result & 1L) != 0 ? ~(result >> 1) : result >> 1;
        return new long[]{delta, index};
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static void requireSuccess(HttpURLConnection connection, String service)
            throws IOException {
        int status = connection.getResponseCode();
        Log.i(TAG, service + " HTTP " + status + " " + connection.getURL());
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = "";
        InputStream error = connection.getErrorStream();
        if (error != null) {
            detail = readResponse(error);
        }
        throw new IOException(
                service + " HTTP " + status
                        + (detail.isEmpty() ? "" : "：" + shorten(detail))
        );
    }

    private static String buildOsrmUrl(List<RoutePoint> points, Profile profile) {
        StringBuilder url = new StringBuilder(profile.osrmEndpoint);
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

    private static void appendWithoutDuplicate(
            List<RoutePoint> destination,
            List<RoutePoint> source
    ) {
        int start = !destination.isEmpty()
                && !source.isEmpty()
                && destination.get(destination.size() - 1).equals(source.get(0))
                ? 1
                : 0;
        destination.addAll(source.subList(start, source.size()));
    }

    private static String readResponse(InputStream inputStream) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(inputStream);
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

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static String shorten(String value) {
        String compact = value.replace('\n', ' ').trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "…";
    }
}
