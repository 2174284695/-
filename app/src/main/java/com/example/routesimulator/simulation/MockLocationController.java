package com.example.routesimulator.simulation;

import android.annotation.SuppressLint;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.SystemClock;

import com.example.routesimulator.model.RoutePoint;

public final class MockLocationController {
    private final LocationManager locationManager;

    public MockLocationController(LocationManager locationManager) {
        this.locationManager = locationManager;
    }

    public void start() {
        addProvider(LocationManager.GPS_PROVIDER, true);
        addProvider(LocationManager.NETWORK_PROVIDER, false);
    }

    public void push(RoutePoint point, float speedMetersPerSecond, float bearingDegrees) {
        pushToProvider(
                LocationManager.GPS_PROVIDER,
                point,
                speedMetersPerSecond,
                bearingDegrees,
                3.0f
        );
        pushToProvider(
                LocationManager.NETWORK_PROVIDER,
                point,
                speedMetersPerSecond,
                bearingDegrees,
                8.0f
        );
    }

    public void stop() {
        removeProvider(LocationManager.GPS_PROVIDER);
        removeProvider(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressLint("InlinedApi")
    @SuppressWarnings("deprecation")
    private void addProvider(String name, boolean supportsAltitude) {
        removeProvider(name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties properties = new ProviderProperties.Builder()
                    .setHasAltitudeSupport(supportsAltitude)
                    .setHasBearingSupport(true)
                    .setHasSpeedSupport(true)
                    .setAccuracy(
                            supportsAltitude
                                    ? ProviderProperties.ACCURACY_FINE
                                    : ProviderProperties.ACCURACY_COARSE
                    )
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .build();
            locationManager.addTestProvider(name, properties);
        } else {
            locationManager.addTestProvider(
                    name,
                    false,
                    false,
                    false,
                    false,
                    true,
                    supportsAltitude,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    supportsAltitude
                            ? ProviderProperties.ACCURACY_FINE
                            : ProviderProperties.ACCURACY_COARSE
            );
        }
        locationManager.setTestProviderEnabled(name, true);
    }

    private void pushToProvider(
            String provider,
            RoutePoint point,
            float speedMetersPerSecond,
            float bearingDegrees,
            float accuracyMeters
    ) {
        Location location = new Location(provider);
        location.setLatitude(point.latitude());
        location.setLongitude(point.longitude());
        location.setAccuracy(accuracyMeters);
        location.setAltitude(0.0);
        location.setSpeed(speedMetersPerSecond);
        location.setBearing(bearingDegrees);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        locationManager.setTestProviderLocation(provider, location);
    }

    private void removeProvider(String provider) {
        try {
            locationManager.removeTestProvider(provider);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // There may be no active test provider yet.
        }
    }
}
