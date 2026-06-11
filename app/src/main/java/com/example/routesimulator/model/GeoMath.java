package com.example.routesimulator.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeoMath {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoMath() {
    }

    public static double distanceMeters(RoutePoint a, RoutePoint b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return EARTH_RADIUS_METERS * 2.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1.0 - h));
    }

    public static float bearingDegrees(RoutePoint from, RoutePoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLon = Math.toRadians(to.longitude() - from.longitude());
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0);
    }

    public static double totalDistanceMeters(List<RoutePoint> points) {
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            total += distanceMeters(points.get(i - 1), points.get(i));
        }
        return total;
    }

    public static List<Double> cumulativeDistances(List<RoutePoint> points) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> distances = new ArrayList<>(points.size());
        double total = 0.0;
        distances.add(total);
        for (int i = 1; i < points.size(); i++) {
            total += distanceMeters(points.get(i - 1), points.get(i));
            distances.add(total);
        }
        return distances;
    }

    public static RouteSample sampleAtDistance(
            List<RoutePoint> points,
            List<Double> cumulativeDistances,
            double requestedDistance
    ) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Route must contain at least one point");
        }
        if (points.size() == 1) {
            return new RouteSample(points.get(0), 0f);
        }

        double total = cumulativeDistances.get(cumulativeDistances.size() - 1);
        double distance = Math.max(0.0, Math.min(requestedDistance, total));
        int segment = 0;
        while (segment < cumulativeDistances.size() - 2
                && cumulativeDistances.get(segment + 1) < distance) {
            segment++;
        }

        RoutePoint start = points.get(segment);
        RoutePoint end = points.get(segment + 1);
        double segmentStart = cumulativeDistances.get(segment);
        double segmentLength = Math.max(
                0.001,
                cumulativeDistances.get(segment + 1) - segmentStart
        );
        double fraction = (distance - segmentStart) / segmentLength;
        return new RouteSample(
                interpolate(start, end, fraction),
                bearingDegrees(start, end)
        );
    }

    private static RoutePoint interpolate(RoutePoint start, RoutePoint end, double fraction) {
        double latitude = start.latitude() + (end.latitude() - start.latitude()) * fraction;
        double lonDelta = end.longitude() - start.longitude();
        if (lonDelta > 180.0) {
            lonDelta -= 360.0;
        } else if (lonDelta < -180.0) {
            lonDelta += 360.0;
        }
        double longitude = start.longitude() + lonDelta * fraction;
        if (longitude > 180.0) {
            longitude -= 360.0;
        } else if (longitude < -180.0) {
            longitude += 360.0;
        }
        return new RoutePoint(latitude, longitude);
    }
}
