package com.example.routesimulator.model;

import java.util.List;

public final class RouteSegmentClassifier {
    private static final double FORCED_POINT_MATCH_METERS = 2.0;
    private static final double FALLBACK_MATCH_METERS = 12.0;
    private static final double OFF_ROAD_SPEED_FACTOR = 0.72;
    private static final double OFF_ROAD_APPROACH_FACTOR = 0.86;
    private static final double OFF_ROAD_APPROACH_METERS = 5.0;

    private RouteSegmentClassifier() {
    }

    public static boolean[] offRoadSegmentsForForcedWaypoints(
            List<RoutePoint> route,
            List<RouteWaypoint> waypoints
    ) {
        boolean[] offRoadSegments = new boolean[Math.max(0, route.size() - 1)];
        if (route.size() < 2 || waypoints.isEmpty()) {
            return offRoadSegments;
        }

        for (RouteWaypoint waypoint : waypoints) {
            if (waypoint.type() != WaypointType.FORCED) {
                continue;
            }
            boolean matched = false;
            int nearestIndex = -1;
            double nearestMeters = Double.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                double distanceMeters = GeoMath.distanceMeters(route.get(i), waypoint.point());
                if (distanceMeters <= FORCED_POINT_MATCH_METERS) {
                    markAdjacentSegments(offRoadSegments, i);
                    matched = true;
                }
                if (distanceMeters < nearestMeters) {
                    nearestMeters = distanceMeters;
                    nearestIndex = i;
                }
            }
            if (!matched && nearestIndex >= 0 && nearestMeters <= FALLBACK_MATCH_METERS) {
                markAdjacentSegments(offRoadSegments, nearestIndex);
            }
        }
        return offRoadSegments;
    }

    public static double speedFactorForProgress(
            double progressMeters,
            List<Double> cumulativeDistances,
            boolean[] offRoadSegments
    ) {
        if (offRoadSegments.length == 0 || cumulativeDistances.size() < 2) {
            return 1.0;
        }
        int segmentIndex = segmentIndexForProgress(progressMeters, cumulativeDistances);
        if (offRoadSegments[Math.min(segmentIndex, offRoadSegments.length - 1)]) {
            return OFF_ROAD_SPEED_FACTOR;
        }

        double segmentStart = cumulativeDistances.get(segmentIndex);
        double segmentEnd = cumulativeDistances.get(segmentIndex + 1);
        boolean nearPrevious = segmentIndex > 0
                && offRoadSegments[segmentIndex - 1]
                && progressMeters - segmentStart <= OFF_ROAD_APPROACH_METERS;
        boolean nearNext = segmentIndex + 1 < offRoadSegments.length
                && offRoadSegments[segmentIndex + 1]
                && segmentEnd - progressMeters <= OFF_ROAD_APPROACH_METERS;
        return nearPrevious || nearNext ? OFF_ROAD_APPROACH_FACTOR : 1.0;
    }

    private static int segmentIndexForProgress(
            double progressMeters,
            List<Double> cumulativeDistances
    ) {
        int segmentIndex = 0;
        while (segmentIndex < cumulativeDistances.size() - 2
                && cumulativeDistances.get(segmentIndex + 1) < progressMeters) {
            segmentIndex++;
        }
        return segmentIndex;
    }

    private static void markAdjacentSegments(boolean[] offRoadSegments, int pointIndex) {
        if (pointIndex > 0) {
            offRoadSegments[pointIndex - 1] = true;
        }
        if (pointIndex < offRoadSegments.length) {
            offRoadSegments[pointIndex] = true;
        }
    }
}
