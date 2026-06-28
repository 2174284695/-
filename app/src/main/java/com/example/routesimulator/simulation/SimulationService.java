package com.example.routesimulator.simulation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import com.example.routesimulator.MainActivity;
import com.example.routesimulator.R;
import com.example.routesimulator.data.RouteStore;
import com.example.routesimulator.model.GeoMath;
import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.RouteSample;
import com.example.routesimulator.model.RouteSegmentClassifier;
import com.example.routesimulator.model.RouteWaypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Instant;

public final class SimulationService extends Service {
    public static final String ACTION_START =
            "com.example.routesimulator.action.START_SIMULATION";
    public static final String ACTION_STOP =
            "com.example.routesimulator.action.STOP_SIMULATION";
    public static final String ACTION_STATE =
            "com.example.routesimulator.action.SIMULATION_STATE";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_SPEED_KMH = "speed_kmh";
    public static final String EXTRA_PROGRESS_METERS = "progress_meters";
    public static final String EXTRA_TOTAL_METERS = "total_meters";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_STEPS = "steps";
    public static final String EXTRA_CADENCE = "cadence";

    private static final String CHANNEL_ID = "route_simulation";
    private static final int NOTIFICATION_ID = 1107;
    private static final long TICK_MILLIS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;

    private RouteStore routeStore;
    private MockLocationController mockLocationController;
    private List<RoutePoint> route;
    private List<Double> cumulativeDistances;
    private boolean[] offRoadSegments;
    private HumanSpeedModel speedModel;
    private SimulatedStepModel stepModel;
    private HealthConnectStepWriter healthConnectStepWriter;
    private double totalDistanceMeters;
    private double progressMeters;
    private long lastTickElapsed;
    private boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        routeStore = new RouteStore(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSimulation("模拟已停止");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            beginSimulation();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        closeHealthWriter();
        cleanUpMockProvider();
        handler.removeCallbacks(tickRunnable);
        running = false;
        routeStore.setSimulationRunning(false);
        super.onDestroy();
    }

    private void beginSimulation() {
        if (getApplicationContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            broadcastState(false, null, 0f, "缺少精确位置权限");
            stopSelf();
            return;
        }
        if (!MockLocationAccess.isAllowed(this)) {
            broadcastState(false, null, 0f, "请先在开发者选项中选择本应用");
            stopSelf();
            return;
        }

        route = routeStore.loadRoute();
        List<RouteWaypoint> waypoints = routeStore.loadWaypoints();
        if (route.size() < 2) {
            broadcastState(false, null, 0f, "路线至少需要两个点");
            stopSelf();
            return;
        }
        if (routeStore.isRoundTripEnabled()) {
            route = buildRoundTripRoute(route);
        }

        float speedKmh = routeStore.loadSpeedKmh();
        int variationPercent = routeStore.loadVariationPercent();
        cumulativeDistances = GeoMath.cumulativeDistances(route);
        offRoadSegments = RouteSegmentClassifier.offRoadSegmentsForForcedWaypoints(
                route,
                waypoints
        );
        totalDistanceMeters = cumulativeDistances.get(cumulativeDistances.size() - 1);
        if (totalDistanceMeters < 1.0) {
            broadcastState(false, null, 0f, "路线过短");
            stopSelf();
            return;
        }

        Notification notification = buildNotification(speedKmh);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        try {
            LocationManager manager = getSystemService(LocationManager.class);
            mockLocationController = new MockLocationController(manager);
            mockLocationController.start();
        } catch (RuntimeException exception) {
            broadcastState(false, null, 0f, "无法启用模拟位置：" + exception.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        speedModel = new HumanSpeedModel(speedKmh, variationPercent);
        stepModel = new SimulatedStepModel();
        if (Build.VERSION.SDK_INT >= 34
                && routeStore.isHealthSyncEnabled()
                && HealthConnectStepWriter.canWrite(this)) {
            healthConnectStepWriter = new HealthConnectStepWriter(this);
        }
        progressMeters = 0.0;
        lastTickElapsed = SystemClock.elapsedRealtime();
        running = true;
        routeStore.setSimulationRunning(true);

        RouteSample initial = GeoMath.sampleAtDistance(route, cumulativeDistances, 0.0);
        mockLocationController.push(initial.point(), speedKmh / 3.6f, initial.bearingDegrees());
        broadcastState(true, initial.point(), speedKmh, "模拟进行中");
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, TICK_MILLIS);
    }

    private void tick() {
        if (!running) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        double deltaSeconds = Math.max(0.05, (now - lastTickElapsed) / 1000.0);
        lastTickElapsed = now;

        double speedMetersPerSecond = speedModel.nextSpeed(
                deltaSeconds,
                routeSpeedFactor(progressMeters)
        );
        double previousProgress = progressMeters;
        progressMeters = Math.min(
                totalDistanceMeters,
                progressMeters + speedMetersPerSecond * deltaSeconds
        );
        double movedMeters = progressMeters - previousProgress;
        long addedSteps = stepModel.advance(movedMeters, speedMetersPerSecond);
        if (Build.VERSION.SDK_INT >= 34 && healthConnectStepWriter != null) {
            addHealthSteps(addedSteps);
        }
        RouteSample sample = GeoMath.sampleAtDistance(
                route,
                cumulativeDistances,
                progressMeters
        );
        mockLocationController.push(
                sample.point(),
                (float) speedMetersPerSecond,
                sample.bearingDegrees()
        );
        broadcastState(
                true,
                sample.point(),
                (float) (speedMetersPerSecond * 3.6),
                "模拟进行中"
        );

        if (progressMeters >= totalDistanceMeters) {
            stopSimulation("路线已完成");
        } else {
            handler.postDelayed(tickRunnable, TICK_MILLIS);
        }
    }

    private static List<RoutePoint> buildRoundTripRoute(List<RoutePoint> oneWayRoute) {
        List<RoutePoint> roundTrip = new ArrayList<>(oneWayRoute);
        for (int i = oneWayRoute.size() - 2; i >= 0; i--) {
            roundTrip.add(oneWayRoute.get(i));
        }
        return roundTrip;
    }

    private double routeSpeedFactor(double currentProgressMeters) {
        if (route == null || route.size() < 2 || totalDistanceMeters <= 0.0) {
            return 1.0;
        }

        double factor = turnSpeedFactor(currentProgressMeters)
                * RouteSegmentClassifier.speedFactorForProgress(
                currentProgressMeters,
                cumulativeDistances,
                offRoadSegments == null ? new boolean[0] : offRoadSegments
        );
        double startRamp = rampFactor(currentProgressMeters, 4.0, 22.0, 0.48, 1.0);
        double remainingMeters = totalDistanceMeters - currentProgressMeters;
        double arrivalRamp = rampFactor(remainingMeters, 4.0, 28.0, 0.42, 1.0);
        return clamp(factor * Math.min(startRamp, arrivalRamp), 0.38, 1.10);
    }

    private double turnSpeedFactor(double currentProgressMeters) {
        if (totalDistanceMeters < 18.0) {
            return 1.0;
        }
        double lookDistance = Math.min(18.0, Math.max(8.0, totalDistanceMeters * 0.02));
        RouteSample before = GeoMath.sampleAtDistance(
                route,
                cumulativeDistances,
                Math.max(0.0, currentProgressMeters - lookDistance)
        );
        RouteSample after = GeoMath.sampleAtDistance(
                route,
                cumulativeDistances,
                Math.min(totalDistanceMeters, currentProgressMeters + lookDistance)
        );
        double turnDegrees = bearingDeltaDegrees(
                before.bearingDegrees(),
                after.bearingDegrees()
        );
        if (turnDegrees >= 70.0) {
            return 0.62;
        }
        if (turnDegrees >= 45.0) {
            return 0.76;
        }
        if (turnDegrees >= 25.0) {
            return 0.88;
        }
        if (turnDegrees <= 8.0) {
            return 1.04;
        }
        return 1.0;
    }

    private static double rampFactor(
            double distanceMeters,
            double minimumDistance,
            double fullSpeedDistance,
            double minimumFactor,
            double maximumFactor
    ) {
        if (distanceMeters <= minimumDistance) {
            return minimumFactor;
        }
        if (distanceMeters >= fullSpeedDistance) {
            return maximumFactor;
        }
        double fraction = (distanceMeters - minimumDistance)
                / (fullSpeedDistance - minimumDistance);
        return minimumFactor + (maximumFactor - minimumFactor) * fraction;
    }

    private static double bearingDeltaDegrees(double first, double second) {
        double delta = Math.abs((first - second + 540.0) % 360.0 - 180.0);
        return Math.min(180.0, delta);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void stopSimulation(String message) {
        if (!running && mockLocationController == null) {
            routeStore.setSimulationRunning(false);
            stopSelf();
            return;
        }
        handler.removeCallbacks(tickRunnable);
        RoutePoint finalPoint = null;
        if (route != null && !route.isEmpty()) {
            finalPoint = route.get(route.size() - 1);
        }
        running = false;
        routeStore.setSimulationRunning(false);
        closeHealthWriter();
        cleanUpMockProvider();
        broadcastState(false, finalPoint, 0f, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanUpMockProvider() {
        if (mockLocationController != null) {
            mockLocationController.stop();
            mockLocationController = null;
        }
    }

    private void closeHealthWriter() {
        if (Build.VERSION.SDK_INT >= 34 && healthConnectStepWriter != null) {
            closeHealthWriterApi34();
            healthConnectStepWriter = null;
        }
    }

    @RequiresApi(34)
    private void addHealthSteps(long addedSteps) {
        healthConnectStepWriter.addSteps(addedSteps, Instant.now());
    }

    @RequiresApi(34)
    private void closeHealthWriterApi34() {
        healthConnectStepWriter.close();
    }

    private void broadcastState(
            boolean isRunning,
            RoutePoint point,
            float currentSpeedKmh,
            String message
    ) {
        Intent state = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, isRunning)
                .putExtra(EXTRA_SPEED_KMH, currentSpeedKmh)
                .putExtra(EXTRA_PROGRESS_METERS, progressMeters)
                .putExtra(EXTRA_TOTAL_METERS, totalDistanceMeters)
                .putExtra(EXTRA_STEPS, stepModel == null ? 0L : stepModel.totalSteps())
                .putExtra(
                        EXTRA_CADENCE,
                        stepModel == null ? 0.0 : stepModel.cadenceStepsPerMinute()
                )
                .putExtra(EXTRA_MESSAGE, message);
        if (point != null) {
            state.putExtra(EXTRA_LATITUDE, point.latitude());
            state.putExtra(EXTRA_LONGITUDE, point.longitude());
        }
        sendBroadcast(state);
    }

    private Notification buildNotification(float speedKmh) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, StopSimulationReceiver.class);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("正在模拟路线")
                .setContentText(String.format(Locale.CHINA, "设定速度 %.1f km/h", speedKmh))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                "停止",
                                stopPendingIntent
                        ).build()
                )
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.simulation_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.simulation_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
