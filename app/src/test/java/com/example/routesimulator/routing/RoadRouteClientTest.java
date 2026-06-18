package com.example.routesimulator.routing;

import com.example.routesimulator.model.RoutePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RoadRouteClientTest {
    @Test
    public void selectWaypointsPreservesEndpointsAndLimit() {
        List<RoutePoint> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            points.add(new RoutePoint(30.0 + i / 1000.0, 120.0));
        }

        List<RoutePoint> selected = RoadRouteClient.selectWaypoints(points, 25);

        assertTrue(selected.size() <= 25);
        assertEquals(points.get(0), selected.get(0));
        assertEquals(points.get(99), selected.get(selected.size() - 1));
    }

    @Test
    public void selectWaypointsPreservesSignificantTurn() {
        List<RoutePoint> points = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            points.add(new RoutePoint(30.0, 120.0 + i / 10_000.0));
        }
        RoutePoint corner = new RoutePoint(30.0, 120.003);
        points.add(corner);
        for (int i = 1; i <= 30; i++) {
            points.add(new RoutePoint(30.0 + i / 10_000.0, 120.003));
        }

        List<RoutePoint> selected = RoadRouteClient.selectWaypoints(points, 25);

        assertTrue(selected.contains(corner));
    }

    @Test
    public void decodePolyline6RestoresCoordinates() throws Exception {
        List<RoutePoint> expected = List.of(
                new RoutePoint(31.230400, 121.473700),
                new RoutePoint(31.230431, 121.473687),
                new RoutePoint(31.230500, 121.473800)
        );

        List<RoutePoint> decoded = RoadRouteClient.decodePolyline6(encodePolyline6(expected));

        assertEquals(expected.size(), decoded.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).latitude(), decoded.get(i).latitude(), 0.000001);
            assertEquals(expected.get(i).longitude(), decoded.get(i).longitude(), 0.000001);
        }
    }

    private static String encodePolyline6(List<RoutePoint> points) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0;
        long previousLongitude = 0;
        for (RoutePoint point : points) {
            long latitude = Math.round(point.latitude() * 1_000_000.0);
            long longitude = Math.round(point.longitude() * 1_000_000.0);
            encodeValue(latitude - previousLatitude, encoded);
            encodeValue(longitude - previousLongitude, encoded);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return encoded.toString();
    }

    private static void encodeValue(long delta, StringBuilder encoded) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            encoded.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        encoded.append((char) (value + 63));
    }
}
