package com.example.routesimulator.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RouteDraftMerger {
    private RouteDraftMerger() {
    }

    public static List<RoutePoint> merge(
            List<RoutePoint> route,
            List<RoutePoint> stroke,
            double joinThresholdMeters
    ) {
        if (route.isEmpty()) {
            return new ArrayList<>(stroke);
        }
        if (stroke.isEmpty()) {
            return new ArrayList<>(route);
        }

        RoutePoint routeStart = route.get(0);
        RoutePoint routeEnd = route.get(route.size() - 1);
        RoutePoint strokeStart = stroke.get(0);
        RoutePoint strokeEnd = stroke.get(stroke.size() - 1);

        double appendForward = GeoMath.distanceMeters(routeEnd, strokeStart);
        double appendReversed = GeoMath.distanceMeters(routeEnd, strokeEnd);
        double prependForward = GeoMath.distanceMeters(routeStart, strokeEnd);
        double prependReversed = GeoMath.distanceMeters(routeStart, strokeStart);

        double best = Math.min(
                Math.min(appendForward, appendReversed),
                Math.min(prependForward, prependReversed)
        );
        if (best > joinThresholdMeters) {
            List<RoutePoint> merged = new ArrayList<>(route);
            merged.addAll(stroke);
            return merged;
        }

        if (best == appendForward) {
            return append(route, stroke, true);
        }
        if (best == appendReversed) {
            return append(route, reversed(stroke), true);
        }
        if (best == prependForward) {
            return prepend(route, stroke, true);
        }
        return prepend(route, reversed(stroke), true);
    }

    private static List<RoutePoint> append(
            List<RoutePoint> route,
            List<RoutePoint> stroke,
            boolean skipStrokeStart
    ) {
        List<RoutePoint> merged = new ArrayList<>(route);
        int start = skipStrokeStart && !stroke.isEmpty() ? 1 : 0;
        merged.addAll(stroke.subList(start, stroke.size()));
        return merged;
    }

    private static List<RoutePoint> prepend(
            List<RoutePoint> route,
            List<RoutePoint> stroke,
            boolean skipStrokeEnd
    ) {
        List<RoutePoint> merged = new ArrayList<>();
        int end = skipStrokeEnd && !stroke.isEmpty() ? stroke.size() - 1 : stroke.size();
        merged.addAll(stroke.subList(0, end));
        merged.addAll(route);
        return merged;
    }

    private static List<RoutePoint> reversed(List<RoutePoint> points) {
        List<RoutePoint> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        return reversed;
    }
}
