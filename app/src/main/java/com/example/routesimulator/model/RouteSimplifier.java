package com.example.routesimulator.model;

import java.util.ArrayList;
import java.util.List;

public final class RouteSimplifier {
    private static final double METERS_PER_DEGREE = 111_320.0;

    private RouteSimplifier() {
    }

    public static List<RoutePoint> simplify(List<RoutePoint> points, double toleranceMeters) {
        if (points.size() < 3) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplifySection(points, 0, points.size() - 1, toleranceMeters, keep);

        List<RoutePoint> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static void simplifySection(
            List<RoutePoint> points,
            int start,
            int end,
            double toleranceMeters,
            boolean[] keep
    ) {
        if (end <= start + 1) {
            return;
        }
        double maxDistance = -1.0;
        int farthest = -1;
        for (int i = start + 1; i < end; i++) {
            double distance = perpendicularDistanceMeters(
                    points.get(i),
                    points.get(start),
                    points.get(end)
            );
            if (distance > maxDistance) {
                maxDistance = distance;
                farthest = i;
            }
        }
        if (maxDistance > toleranceMeters) {
            keep[farthest] = true;
            simplifySection(points, start, farthest, toleranceMeters, keep);
            simplifySection(points, farthest, end, toleranceMeters, keep);
        }
    }

    private static double perpendicularDistanceMeters(
            RoutePoint point,
            RoutePoint start,
            RoutePoint end
    ) {
        double referenceLatitude = Math.toRadians(
                (point.latitude() + start.latitude() + end.latitude()) / 3.0
        );
        double lonScale = Math.cos(referenceLatitude) * METERS_PER_DEGREE;

        double px = (point.longitude() - start.longitude()) * lonScale;
        double py = (point.latitude() - start.latitude()) * METERS_PER_DEGREE;
        double ex = (end.longitude() - start.longitude()) * lonScale;
        double ey = (end.latitude() - start.latitude()) * METERS_PER_DEGREE;
        double lengthSquared = ex * ex + ey * ey;
        if (lengthSquared == 0.0) {
            return Math.hypot(px, py);
        }
        double t = Math.max(0.0, Math.min(1.0, (px * ex + py * ey) / lengthSquared));
        return Math.hypot(px - t * ex, py - t * ey);
    }
}
