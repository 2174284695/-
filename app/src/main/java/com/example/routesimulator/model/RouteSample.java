package com.example.routesimulator.model;

public final class RouteSample {
    private final RoutePoint point;
    private final float bearingDegrees;

    public RouteSample(RoutePoint point, float bearingDegrees) {
        this.point = point;
        this.bearingDegrees = bearingDegrees;
    }

    public RoutePoint point() {
        return point;
    }

    public float bearingDegrees() {
        return bearingDegrees;
    }
}
