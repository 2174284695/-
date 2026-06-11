package com.example.routesimulator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.example.routesimulator.data.RouteStore;
import com.example.routesimulator.model.GeoMath;
import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.SavedRoute;
import com.example.routesimulator.routing.RoadRouteClient;
import com.example.routesimulator.simulation.MockLocationAccess;
import com.example.routesimulator.simulation.SimulatedStepModel;
import com.example.routesimulator.simulation.SimulationService;
import com.example.routesimulator.ui.RouteOverlayView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final String MAP_STYLE_URI =
            "https://tiles.openfreemap.org/styles/liberty";
    private static final double MIN_MAP_ZOOM = 2.0;
    private static final double MAX_MAP_ZOOM = 20.0;
    private static final int REQUEST_LOCATION_PERMISSIONS = 42;
    private static final int MAX_HISTORY = 30;
    private static final long LOCATION_TIMEOUT_MILLIS = 12_000L;
    private static final String HEALTH_WRITE_STEPS =
            "android.permission.health.WRITE_STEPS";
    private static final DateTimeFormatter ROUTE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA);
    private static final DateTimeFormatter ROUTE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA);

    private final List<RoutePoint> route = new ArrayList<>();
    private final Deque<List<RoutePoint>> history = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService routingExecutor = Executors.newSingleThreadExecutor();
    private final RoadRouteClient roadRouteClient = new RoadRouteClient();

    private RouteStore routeStore;
    private MapView mapView;
    private MapLibreMap map;
    private RouteOverlayView routeOverlay;
    private TextView statusText;
    private TextView modeHintText;
    private TextView routeStatsText;
    private TextView speedValueText;
    private TextView variationValueText;
    private Button setupButton;
    private Button browseButton;
    private Button pointButton;
    private Button drawButton;
    private Button undoButton;
    private Button clearButton;
    private Button fitButton;
    private Button saveRouteButton;
    private Button routeLibraryButton;
    private Button roadSnapButton;
    private Button startButton;
    private SeekBar speedSeekBar;
    private SeekBar variationSeekBar;
    private CheckBox healthSyncCheckBox;
    private boolean mapReady;
    private boolean simulationRunning;
    private boolean pendingStartAfterPermission;
    private boolean stateReceiverRegistered;
    private boolean updatingHealthSyncCheckBox;
    private boolean mapStyleLoading;
    private boolean roadSnapInProgress;
    private String currentSavedRouteName;
    private boolean pendingLocateAfterPermission;
    private boolean locationRequestInProgress;
    private CancellationSignal locationCancellationSignal;
    private LocationListener legacyLocationListener;
    private Location locationFallback;
    private final Runnable locationTimeoutRunnable = () -> {
        if (!locationRequestInProgress) {
            return;
        }
        Location fallback = locationFallback;
        cancelLocationRequest();
        locationFallback = null;
        if (fallback != null) {
            showLocationOnMap(fallback);
            Toast.makeText(this, "已定位到系统记录的最近位置", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(
                    this,
                    "暂时无法获取当前位置，请检查系统定位信号",
                    Toast.LENGTH_SHORT
            ).show();
        }
    };

    private final ActivityResultLauncher<Set<String>> healthPermissionLauncher =
            registerForActivityResult(
                    PermissionController.createRequestPermissionResultContract(),
                    grantedPermissions -> {
                        boolean granted = grantedPermissions.contains(HEALTH_WRITE_STEPS);
                        if (routeStore != null) {
                            routeStore.setHealthSyncEnabled(granted);
                        }
                        updatingHealthSyncCheckBox = true;
                        if (healthSyncCheckBox != null) {
                            healthSyncCheckBox.setChecked(granted);
                        }
                        updatingHealthSyncCheckBox = false;
                        Toast.makeText(
                                this,
                                granted
                                        ? "模拟步数将同步到 Health Connect"
                                        : "未授权，步数只在路线模拟器内显示",
                                Toast.LENGTH_LONG
                        ).show();
                    }
            );

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(SimulationService.EXTRA_RUNNING, false);
            String message = intent.getStringExtra(SimulationService.EXTRA_MESSAGE);
            simulationRunning = running;
            setEditorEnabled(!running);
            startButton.setText(running ? "停止模拟" : "开始模拟");

            if (intent.hasExtra(SimulationService.EXTRA_LATITUDE)) {
                routeOverlay.setActivePoint(new RoutePoint(
                        intent.getDoubleExtra(SimulationService.EXTRA_LATITUDE, 0.0),
                        intent.getDoubleExtra(SimulationService.EXTRA_LONGITUDE, 0.0)
                ));
            } else if (!running) {
                routeOverlay.setActivePoint(null);
            }

            if (running) {
                float currentSpeed = intent.getFloatExtra(
                        SimulationService.EXTRA_SPEED_KMH,
                        0f
                );
                double progress = intent.getDoubleExtra(
                        SimulationService.EXTRA_PROGRESS_METERS,
                        0.0
                );
                double total = intent.getDoubleExtra(
                        SimulationService.EXTRA_TOTAL_METERS,
                        0.0
                );
                long steps = intent.getLongExtra(SimulationService.EXTRA_STEPS, 0L);
                routeStatsText.setText(String.format(
                        Locale.CHINA,
                        "进行中 %.1f km/h · %,d 步 · %.0f/%.0f m",
                        currentSpeed,
                        steps,
                        progress,
                        total
                ));
            } else {
                updateRouteStats();
                if (message != null && !message.isEmpty()) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        routeStore = new RouteStore(this);
        route.addAll(routeStore.loadRoute());

        buildInterface(savedInstanceState);
        loadSavedSettings();
        updateRouteStats();
        setMode(RouteOverlayView.Mode.BROWSE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
        registerStateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        mapView.post(this::ensureMapIsUsable);
        refreshSystemStatus();
        refreshHealthSyncControl();
        simulationRunning = routeStore.isSimulationRunning();
        setEditorEnabled(!simulationRunning);
        startButton.setText(simulationRunning ? "停止模拟" : "开始模拟");
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterStateReceiver();
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelLocationRequest();
        locationFallback = null;
        routingExecutor.shutdownNow();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION_PERMISSIONS) {
            return;
        }
        refreshSystemStatus();
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            boolean shouldLocate = pendingLocateAfterPermission;
            boolean shouldStart = pendingStartAfterPermission;
            pendingLocateAfterPermission = false;
            pendingStartAfterPermission = false;
            if (shouldLocate) {
                centerOnCurrentLocation();
            }
            if (shouldStart) {
                startSimulation();
            }
            return;
        }
        boolean locateWasPending = pendingLocateAfterPermission;
        pendingLocateAfterPermission = false;
        pendingStartAfterPermission = false;
        Toast.makeText(
                this,
                locateWasPending
                        ? "需要精确位置权限才能定位当前位置"
                        : "需要精确位置权限才能运行前台模拟服务",
                Toast.LENGTH_LONG
        ).show();
    }

    private void buildInterface(Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.panel));

        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        mapView.addOnDidFailLoadingMapListener(error -> {
            mapStyleLoading = false;
            mapReady = false;
        });
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        routeOverlay = new RouteOverlayView(this);
        routeOverlay.setRoute(route);
        routeOverlay.setOnRouteChangedListener(previousRoute -> {
            pushHistory(previousRoute);
            onRouteEdited();
        });
        root.addView(routeOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(buildStatusPanel(), topPanelParams());
        root.addView(buildZoomControls(), zoomControlsParams());
        root.addView(buildAttribution(), attributionParams());
        root.addView(buildControlPanel(), bottomPanelParams());
        setContentView(root);

        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            map.setMinZoomPreference(MIN_MAP_ZOOM);
            map.setMaxZoomPreference(MAX_MAP_ZOOM);
            UiSettings uiSettings = map.getUiSettings();
            uiSettings.setZoomGesturesEnabled(true);
            uiSettings.setDoubleTapGesturesEnabled(true);
            uiSettings.setQuickZoomGesturesEnabled(true);
            routeOverlay.setMap(map);
            map.addOnMapClickListener(this::onMapClick);
            loadMapStyle(true);
        });
    }

    private void loadMapStyle(boolean positionAfterLoad) {
        if (map == null || mapStyleLoading) {
            return;
        }
        mapReady = false;
        mapStyleLoading = true;
        map.setStyle(new Style.Builder().fromUri(MAP_STYLE_URI), style -> {
            mapStyleLoading = false;
            mapReady = true;
            if (positionAfterLoad) {
                if (route.isEmpty()) {
                    map.setCameraPosition(new CameraPosition.Builder()
                            .target(new LatLng(31.2304, 121.4737))
                            .zoom(14.0)
                            .build());
                } else {
                    fitRoute();
                }
            }
            routeOverlay.invalidate();
            mapView.invalidate();
        });
    }

    private void ensureMapIsUsable() {
        if (map == null || mapStyleLoading || mapView.isDestroyed()) {
            return;
        }
        Style style = map.getStyle();
        if (style == null || !style.isFullyLoaded()) {
            loadMapStyle(false);
            return;
        }
        mapReady = true;
        routeOverlay.invalidate();
        mapView.invalidate();
    }

    private View buildStatusPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(10), dp(10));
        panel.setBackgroundResource(R.drawable.bg_panel);
        panel.setElevation(dp(6));

        statusText = textView("", 14, R.color.ink);
        statusText.setMaxLines(2);
        panel.addView(statusText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        setupButton = secondaryButton("去设置");
        setupButton.setOnClickListener(view -> openDeveloperSettings());
        panel.addView(setupButton, new LinearLayout.LayoutParams(
                dp(82),
                dp(42)
        ));
        return panel;
    }

    private View buildAttribution() {
        TextView attribution = textView(
                "© OpenFreeMap · OpenMapTiles · OpenStreetMap",
                10,
                R.color.muted
        );
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(dp(8), dp(4), dp(8), dp(4));
        attribution.setBackgroundColor(Color.argb(220, 255, 255, 255));
        attribution.setOnClickListener(view -> {
            Intent browser = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.openstreetmap.org/copyright")
            );
            startActivity(browser);
        });
        return attribution;
    }

    private View buildZoomControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundResource(R.drawable.bg_panel);
        controls.setElevation(dp(6));
        controls.setPadding(dp(4), dp(4), dp(4), dp(4));

        Button zoomIn = secondaryButton("+");
        zoomIn.setTextSize(24);
        zoomIn.setContentDescription("放大地图");
        zoomIn.setOnClickListener(view -> {
            if (mapReady) {
                map.animateCamera(CameraUpdateFactory.zoomIn(), 180);
            }
        });
        controls.addView(zoomIn, new LinearLayout.LayoutParams(dp(46), dp(46)));

        Button zoomOut = secondaryButton("−");
        zoomOut.setTextSize(24);
        zoomOut.setContentDescription("缩小地图");
        zoomOut.setOnClickListener(view -> {
            if (mapReady) {
                map.animateCamera(CameraUpdateFactory.zoomOut(), 180);
            }
        });
        LinearLayout.LayoutParams zoomOutParams = new LinearLayout.LayoutParams(
                dp(46),
                dp(46)
        );
        zoomOutParams.topMargin = dp(4);
        controls.addView(zoomOut, zoomOutParams);

        Button locate = secondaryButton("定位");
        locate.setTextSize(12);
        locate.setContentDescription("定位到我现在的位置");
        locate.setOnClickListener(view -> centerOnCurrentLocation());
        LinearLayout.LayoutParams locateParams = new LinearLayout.LayoutParams(
                dp(46),
                dp(46)
        );
        locateParams.topMargin = dp(4);
        controls.addView(locate, locateParams);
        return controls;
    }

    private View buildControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackgroundResource(R.drawable.bg_panel);
        panel.setElevation(dp(8));

        TextView title = textView("绘制路线", 19, R.color.ink);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(title);

        LinearLayout modeRow = horizontalRow();
        browseButton = secondaryButton("浏览");
        pointButton = secondaryButton("点选");
        drawButton = secondaryButton("手绘");
        browseButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.BROWSE));
        pointButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.POINT));
        drawButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.DRAW));
        addEqualButtons(modeRow, browseButton, pointButton, drawButton);
        panel.addView(modeRow, marginTopParams(dp(8)));

        modeHintText = textView("", 13, R.color.muted);
        panel.addView(modeHintText, marginTopParams(dp(5)));

        routeStatsText = textView("", 14, R.color.ink);
        routeStatsText.setTypeface(
                routeStatsText.getTypeface(),
                android.graphics.Typeface.BOLD
        );
        panel.addView(routeStatsText, marginTopParams(dp(8)));

        LinearLayout editRow = horizontalRow();
        undoButton = secondaryButton("撤销");
        clearButton = secondaryButton("清空");
        fitButton = secondaryButton("显示全程");
        undoButton.setOnClickListener(view -> undoEdit());
        clearButton.setOnClickListener(view -> clearRoute());
        fitButton.setOnClickListener(view -> fitRoute());
        addEqualButtons(editRow, undoButton, clearButton, fitButton);
        panel.addView(editRow, marginTopParams(dp(8)));

        LinearLayout savedRouteRow = horizontalRow();
        saveRouteButton = secondaryButton("保存路线");
        routeLibraryButton = secondaryButton("路线库");
        roadSnapButton = secondaryButton("道路吸附");
        saveRouteButton.setOnClickListener(view -> showSaveRouteDialog());
        routeLibraryButton.setOnClickListener(view -> showRouteLibraryDialog());
        roadSnapButton.setOnClickListener(view -> showRoadSnapDialog());
        addEqualButtons(
                savedRouteRow,
                saveRouteButton,
                routeLibraryButton,
                roadSnapButton
        );
        panel.addView(savedRouteRow, marginTopParams(dp(7)));

        LinearLayout speedHeader = horizontalRow();
        speedHeader.addView(textView("设定速度", 14, R.color.ink));
        speedHeader.addView(weightedSpace());
        speedValueText = textView("", 14, R.color.primary);
        speedHeader.addView(speedValueText);
        panel.addView(speedHeader, marginTopParams(dp(10)));

        speedSeekBar = new SeekBar(this);
        speedSeekBar.setMax(290);
        speedSeekBar.setProgress(40);
        speedSeekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingLabels();
                saveSettings();
                updateRouteStats();
            }
        });
        panel.addView(speedSeekBar, compactSeekBarParams());

        LinearLayout variationHeader = horizontalRow();
        variationHeader.addView(textView("自然速度波动", 14, R.color.ink));
        variationHeader.addView(weightedSpace());
        variationValueText = textView("", 14, R.color.primary);
        variationHeader.addView(variationValueText);
        panel.addView(variationHeader);

        variationSeekBar = new SeekBar(this);
        variationSeekBar.setMax(14);
        variationSeekBar.setProgress(5);
        variationSeekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingLabels();
                saveSettings();
            }
        });
        panel.addView(variationSeekBar, compactSeekBarParams());

        healthSyncCheckBox = new CheckBox(this);
        healthSyncCheckBox.setText(R.string.health_sync_label);
        healthSyncCheckBox.setTextSize(13);
        healthSyncCheckBox.setTextColor(getColor(R.color.ink));
        healthSyncCheckBox.setMinHeight(0);
        healthSyncCheckBox.setPadding(0, 0, 0, 0);
        healthSyncCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingHealthSyncCheckBox) {
                return;
            }
            if (!isChecked) {
                routeStore.setHealthSyncEnabled(false);
                return;
            }
            enableHealthSync();
        });
        panel.addView(healthSyncCheckBox);

        startButton = new Button(this);
        startButton.setText("开始模拟");
        startButton.setTextColor(Color.WHITE);
        startButton.setTextSize(16);
        startButton.setAllCaps(false);
        startButton.setBackgroundResource(R.drawable.bg_button_primary);
        startButton.setOnClickListener(view -> {
            if (simulationRunning) {
                stopSimulation();
            } else {
                validateAndStart();
            }
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        startParams.topMargin = dp(5);
        panel.addView(startButton, startParams);
        return panel;
    }

    private boolean onMapClick(LatLng point) {
        if (simulationRunning || routeOverlay.getMode() != RouteOverlayView.Mode.POINT) {
            return false;
        }
        pushHistory(new ArrayList<>(route));
        route.add(new RoutePoint(point.getLatitude(), point.getLongitude()));
        onRouteEdited();
        return true;
    }

    private void setMode(RouteOverlayView.Mode mode) {
        if (simulationRunning && mode != RouteOverlayView.Mode.BROWSE) {
            return;
        }
        routeOverlay.setMode(mode);
        browseButton.setSelected(mode == RouteOverlayView.Mode.BROWSE);
        pointButton.setSelected(mode == RouteOverlayView.Mode.POINT);
        drawButton.setSelected(mode == RouteOverlayView.Mode.DRAW);
        if (mode == RouteOverlayView.Mode.POINT) {
            modeHintText.setText("点按地图依次添加路线点");
        } else if (mode == RouteOverlayView.Mode.DRAW) {
            modeHintText.setText("按住并沿路线滑动，松手完成一段");
        } else {
            modeHintText.setText("拖动地图浏览，双指缩放");
        }
    }

    private void onRouteEdited() {
        currentSavedRouteName = null;
        routeStore.saveRoute(route);
        routeOverlay.setActivePoint(null);
        routeOverlay.invalidate();
        updateRouteStats();
        updateEditButtons();
    }

    private void pushHistory(List<RoutePoint> snapshot) {
        if (history.size() >= MAX_HISTORY) {
            history.removeFirst();
        }
        history.addLast(new ArrayList<>(snapshot));
    }

    private void undoEdit() {
        if (history.isEmpty()) {
            return;
        }
        route.clear();
        route.addAll(history.removeLast());
        onRouteEdited();
    }

    private void clearRoute() {
        if (route.isEmpty()) {
            return;
        }
        pushHistory(new ArrayList<>(route));
        route.clear();
        onRouteEdited();
    }

    private void showSaveRouteDialog() {
        if (route.size() < 2) {
            Toast.makeText(this, "至少绘制两个路线点后才能保存", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentSavedRouteName != null
                ? currentSavedRouteName
                : "路线 " + ROUTE_NAME_FORMAT.format(LocalDateTime.now()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("保存路线")
                .setMessage("同名路线会更新为当前绘制内容")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("请输入路线名称");
                        return;
                    }
                    SavedRoute savedRoute = routeStore.saveNamedRoute(name, route);
                    currentSavedRouteName = savedRoute.name();
                    updateEditButtons();
                    Toast.makeText(this, "路线已保存", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showRouteLibraryDialog() {
        List<SavedRoute> savedRoutes = routeStore.loadSavedRoutes();
        if (savedRoutes.isEmpty()) {
            Toast.makeText(this, "还没有保存过路线", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(4), dp(10), dp(8));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("路线库")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .create();

        for (SavedRoute savedRoute : savedRoutes) {
            LinearLayout row = horizontalRow();
            row.setPadding(0, dp(5), 0, dp(5));

            LocalDateTime updatedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(savedRoute.updatedAtMillis()),
                    ZoneId.systemDefault()
            );
            TextView details = textView(String.format(
                    Locale.CHINA,
                    "%s\n%.2f km · %d 点 · %s",
                    savedRoute.name(),
                    savedRoute.distanceMeters() / 1000.0,
                    savedRoute.points().size(),
                    ROUTE_DATE_FORMAT.format(updatedAt)
            ), 14, R.color.ink);
            details.setMaxLines(2);
            row.addView(details, new LinearLayout.LayoutParams(
                    0,
                    dp(56),
                    1f
            ));

            Button load = secondaryButton("载入");
            load.setOnClickListener(view -> {
                applySavedRoute(savedRoute);
                dialog.dismiss();
            });
            LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(
                    dp(58),
                    dp(40)
            );
            loadParams.leftMargin = dp(6);
            row.addView(load, loadParams);

            Button delete = secondaryButton("删除");
            delete.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("删除路线")
                    .setMessage("确定删除“" + savedRoute.name() + "”吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (ignored, which) -> {
                        routeStore.deleteSavedRoute(savedRoute.id());
                        if (savedRoute.name().equals(currentSavedRouteName)) {
                            currentSavedRouteName = null;
                        }
                        dialog.dismiss();
                        updateEditButtons();
                        showRouteLibraryDialog();
                    })
                    .show());
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    dp(58),
                    dp(40)
            );
            deleteParams.leftMargin = dp(6);
            row.addView(delete, deleteParams);
            list.addView(row);
        }
        dialog.show();
    }

    private void applySavedRoute(SavedRoute savedRoute) {
        pushHistory(new ArrayList<>(route));
        route.clear();
        route.addAll(savedRoute.points());
        currentSavedRouteName = savedRoute.name();
        routeStore.saveRoute(route);
        routeOverlay.setActivePoint(null);
        routeOverlay.invalidate();
        updateRouteStats();
        setMode(RouteOverlayView.Mode.BROWSE);
        mapView.post(this::fitRoute);
        Toast.makeText(this, "已载入“" + savedRoute.name() + "”", Toast.LENGTH_SHORT).show();
    }

    private void showRoadSnapDialog() {
        if (route.size() < 2) {
            Toast.makeText(this, "至少绘制两个路线点后才能吸附", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("吸附到道路")
                .setMessage(
                        "将把最多 25 个代表性路线点发送给 OpenStreetMap.de 路由服务。"
                                + "原路线会保留在撤销历史中。"
                )
                .setItems(
                        new String[]{"步行道路", "驾车道路"},
                        (dialog, which) -> snapRouteToRoads(
                                which == 0
                                        ? RoadRouteClient.Profile.FOOT
                                        : RoadRouteClient.Profile.DRIVING
                        )
                )
                .setNegativeButton("取消", null)
                .show();
    }

    private void snapRouteToRoads(RoadRouteClient.Profile profile) {
        if (roadSnapInProgress || simulationRunning) {
            return;
        }
        List<RoutePoint> original = new ArrayList<>(route);
        roadSnapInProgress = true;
        roadSnapButton.setText("吸附中…");
        setEditorEnabled(false);
        startButton.setEnabled(false);

        routingExecutor.execute(() -> {
            try {
                List<RoutePoint> snapped = roadRouteClient.route(original, profile);
                mainHandler.post(() -> completeRoadSnap(original, snapped, null));
            } catch (Exception exception) {
                mainHandler.post(() -> completeRoadSnap(original, null, exception));
            }
        });
    }

    private void completeRoadSnap(
            List<RoutePoint> original,
            List<RoutePoint> snapped,
            Exception error
    ) {
        if (isDestroyed()) {
            return;
        }
        roadSnapInProgress = false;
        roadSnapButton.setText("道路吸附");
        startButton.setEnabled(true);
        setEditorEnabled(!simulationRunning);

        if (error != null || snapped == null) {
            String detail = error == null ? "" : error.getMessage();
            Toast.makeText(
                    this,
                    detail == null || detail.isEmpty()
                            ? "道路吸附失败，请检查网络后重试"
                            : "道路吸附失败：" + detail,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        pushHistory(original);
        route.clear();
        route.addAll(snapped);
        onRouteEdited();
        setMode(RouteOverlayView.Mode.BROWSE);
        mapView.post(this::fitRoute);
        Toast.makeText(this, "路线已吸附到道路，可使用撤销恢复", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("MissingPermission")
    private void centerOnCurrentLocation() {
        if (simulationRunning) {
            Toast.makeText(this, "请先停止模拟，再获取真实当前位置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocateAfterPermission = true;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION_PERMISSIONS
            );
            return;
        }
        if (!mapReady || map == null) {
            ensureMapIsUsable();
            Toast.makeText(this, "地图正在加载，请稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationManager manager = getSystemService(LocationManager.class);
        if (!isLocationEnabled(manager)) {
            Toast.makeText(this, "请先打开系统定位服务", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (locationRequestInProgress) {
            Toast.makeText(this, "正在获取当前位置", Toast.LENGTH_SHORT).show();
            return;
        }

        Location lastKnown = findBestLastKnownLocation(manager);
        if (lastKnown != null
                && System.currentTimeMillis() - lastKnown.getTime() <= 120_000L) {
            showLocationOnMap(lastKnown);
            return;
        }

        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        String provider = manager.getBestProvider(criteria, true);
        if (provider == null) {
            provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : LocationManager.GPS_PROVIDER;
        }

        locationFallback = lastKnown;
        locationRequestInProgress = true;
        mainHandler.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MILLIS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationCancellationSignal = new CancellationSignal();
            manager.getCurrentLocation(
                    provider,
                    locationCancellationSignal,
                    getMainExecutor(),
                    this::completeLocationRequest
            );
            return;
        }

        legacyLocationListener = this::completeLocationRequest;
        manager.requestSingleUpdate(provider, legacyLocationListener, Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    private Location findBestLastKnownLocation(LocationManager manager) {
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null || candidate.isFromMockProvider()) {
                    continue;
                }
                if (best == null || candidate.getTime() > best.getTime()) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return best;
    }

    private void completeLocationRequest(Location location) {
        if (!locationRequestInProgress) {
            return;
        }
        Location fallback = locationFallback;
        cancelLocationRequest();
        locationFallback = null;
        if (location != null && !location.isFromMockProvider()) {
            showLocationOnMap(location);
        } else if (fallback != null) {
            showLocationOnMap(fallback);
            Toast.makeText(this, "已定位到系统记录的最近位置", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "未能获取当前位置", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLocationOnMap(Location location) {
        setMode(RouteOverlayView.Mode.BROWSE);
        map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()),
                        17.0
                ),
                650
        );
    }

    @SuppressLint("MissingPermission")
    private void cancelLocationRequest() {
        mainHandler.removeCallbacks(locationTimeoutRunnable);
        if (locationCancellationSignal != null) {
            locationCancellationSignal.cancel();
            locationCancellationSignal = null;
        }
        if (legacyLocationListener != null) {
            LocationManager manager = getSystemService(LocationManager.class);
            if (manager != null) {
                try {
                    manager.removeUpdates(legacyLocationListener);
                } catch (SecurityException ignored) {
                    // Permission may have been revoked while the request was active.
                }
            }
            legacyLocationListener = null;
        }
        locationRequestInProgress = false;
    }

    private void fitRoute() {
        if (!mapReady || route.isEmpty()) {
            return;
        }
        if (route.size() == 1) {
            RoutePoint point = route.get(0);
            map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                            new LatLng(point.latitude(), point.longitude()),
                            16.0
                    ),
                    500
            );
            return;
        }

        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (RoutePoint point : route) {
            bounds.include(new LatLng(point.latitude(), point.longitude()));
        }
        map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(72)),
                650
        );
    }

    private void validateAndStart() {
        if (route.size() < 2 || GeoMath.totalDistanceMeters(route) < 1.0) {
            Toast.makeText(this, "请先绘制一条有效路线", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MockLocationAccess.isAllowed(this)) {
            Toast.makeText(this, "请先在开发者选项中选择“路线模拟器”", Toast.LENGTH_LONG).show();
            openDeveloperSettings();
            return;
        }
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (!isLocationEnabled(locationManager)) {
            Toast.makeText(this, "请先打开系统定位服务", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(
                    permissions.toArray(new String[0]),
                    REQUEST_LOCATION_PERMISSIONS
            );
            return;
        }
        startSimulation();
    }

    private void startSimulation() {
        routeStore.saveRoute(route);
        saveSettings();
        Intent service = new Intent(this, SimulationService.class)
                .setAction(SimulationService.ACTION_START);
        try {
            startForegroundService(service);
            simulationRunning = true;
            setEditorEnabled(false);
            startButton.setText("停止模拟");
        } catch (RuntimeException exception) {
            Toast.makeText(
                    this,
                    "无法启动模拟服务：" + exception.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void stopSimulation() {
        Intent service = new Intent(this, SimulationService.class)
                .setAction(SimulationService.ACTION_STOP);
        startService(service);
    }

    private void setEditorEnabled(boolean enabled) {
        if (!enabled) {
            setMode(RouteOverlayView.Mode.BROWSE);
        }
        pointButton.setEnabled(enabled);
        drawButton.setEnabled(enabled);
        undoButton.setEnabled(enabled && !history.isEmpty());
        clearButton.setEnabled(enabled && !route.isEmpty());
        saveRouteButton.setEnabled(enabled && route.size() >= 2);
        routeLibraryButton.setEnabled(enabled);
        roadSnapButton.setEnabled(enabled && route.size() >= 2 && !roadSnapInProgress);
        speedSeekBar.setEnabled(enabled);
        variationSeekBar.setEnabled(enabled);
        healthSyncCheckBox.setEnabled(enabled && isHealthSyncAvailable());
    }

    private void updateEditButtons() {
        undoButton.setEnabled(!simulationRunning && !history.isEmpty());
        clearButton.setEnabled(!simulationRunning && !route.isEmpty());
        fitButton.setEnabled(!route.isEmpty());
        saveRouteButton.setEnabled(!simulationRunning && route.size() >= 2);
        routeLibraryButton.setEnabled(!simulationRunning);
        roadSnapButton.setEnabled(
                !simulationRunning && !roadSnapInProgress && route.size() >= 2
        );
        routeLibraryButton.setText(String.format(
                Locale.CHINA,
                "路线库 (%d)",
                routeStore.loadSavedRoutes().size()
        ));
    }

    private void updateRouteStats() {
        double meters = GeoMath.totalDistanceMeters(route);
        float speedKmh = selectedSpeedKmh();
        double minutes = speedKmh <= 0f ? 0.0 : (meters / 1000.0) / speedKmh * 60.0;
        long estimatedSteps = Math.round(
                meters / SimulatedStepModel.estimateStrideMeters(speedKmh / 3.6)
        );
        routeStatsText.setText(String.format(
                Locale.CHINA,
                "%d 个点 · %.2f km · 约 %.0f 分钟 / %,d 步",
                route.size(),
                meters / 1000.0,
                minutes,
                estimatedSteps
        ));
        updateEditButtons();
    }

    private void loadSavedSettings() {
        float speed = routeStore.loadSpeedKmh();
        int variation = routeStore.loadVariationPercent();
        speedSeekBar.setProgress(Math.round((speed - 1.0f) * 10f));
        variationSeekBar.setProgress(Math.max(0, variation - 1));
        updateSettingLabels();
        refreshHealthSyncControl();
    }

    private void saveSettings() {
        routeStore.saveSettings(selectedSpeedKmh(), selectedVariationPercent());
    }

    private void updateSettingLabels() {
        speedValueText.setText(String.format(
                Locale.CHINA,
                "%.1f km/h",
                selectedSpeedKmh()
        ));
        variationValueText.setText(String.format(
                Locale.CHINA,
                "±%d%%",
                selectedVariationPercent()
        ));
    }

    private float selectedSpeedKmh() {
        return 1.0f + speedSeekBar.getProgress() / 10.0f;
    }

    private int selectedVariationPercent() {
        return 1 + variationSeekBar.getProgress();
    }

    private void enableHealthSync() {
        if (!isHealthSyncAvailable()) {
            setHealthSyncChecked(false);
            Toast.makeText(
                    this,
                    "Health Connect 步数同步需要 Android 14 或更高版本",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (hasHealthWritePermission()) {
            routeStore.setHealthSyncEnabled(true);
            return;
        }
        setHealthSyncChecked(false);
        healthPermissionLauncher.launch(Collections.singleton(HEALTH_WRITE_STEPS));
    }

    private void refreshHealthSyncControl() {
        if (healthSyncCheckBox == null || routeStore == null) {
            return;
        }
        boolean available = isHealthSyncAvailable();
        boolean enabled = available
                && routeStore.isHealthSyncEnabled()
                && hasHealthWritePermission();
        if (!enabled && routeStore.isHealthSyncEnabled()) {
            routeStore.setHealthSyncEnabled(false);
        }
        healthSyncCheckBox.setText(
                available
                        ? R.string.health_sync_label
                        : R.string.health_sync_unavailable
        );
        setHealthSyncChecked(enabled);
        healthSyncCheckBox.setEnabled(!simulationRunning && available);
    }

    private void setHealthSyncChecked(boolean checked) {
        updatingHealthSyncCheckBox = true;
        healthSyncCheckBox.setChecked(checked);
        updatingHealthSyncCheckBox = false;
    }

    private boolean isHealthSyncAvailable() {
        return Build.VERSION.SDK_INT >= 34
                && HealthConnectClient.getSdkStatus(this)
                == HealthConnectClient.SDK_AVAILABLE;
    }

    private boolean hasHealthWritePermission() {
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(HEALTH_WRITE_STEPS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshSystemStatus() {
        boolean mockAllowed = MockLocationAccess.isAllowed(this);
        boolean permissionGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        LocationManager manager = getSystemService(LocationManager.class);
        boolean locationEnabled = isLocationEnabled(manager);

        if (mockAllowed && permissionGranted && locationEnabled) {
            statusText.setText("系统已就绪，可以开始模拟");
            statusText.setBackgroundResource(R.drawable.bg_status_ok);
            statusText.setPadding(dp(10), dp(7), dp(10), dp(7));
            setupButton.setVisibility(View.GONE);
        } else {
            String issue;
            if (!mockAllowed) {
                issue = "请在开发者选项中选择本应用为模拟位置应用";
                setupButton.setText("去设置");
            } else if (!locationEnabled) {
                issue = "系统定位服务尚未开启";
                setupButton.setText("开启定位");
            } else {
                issue = "首次开始时需要授予精确位置权限";
                setupButton.setText("知道了");
            }
            statusText.setText(issue);
            statusText.setBackgroundResource(R.drawable.bg_status_warn);
            statusText.setPadding(dp(10), dp(7), dp(10), dp(7));
            setupButton.setVisibility(View.VISIBLE);
        }
    }

    private void openDeveloperSettings() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (MockLocationAccess.isAllowed(this)
                && !isLocationEnabled(manager)) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (ActivityNotFoundException exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @SuppressLint("InlinedApi")
    private void registerStateReceiver() {
        if (stateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(SimulationService.ACTION_STATE);
        registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        stateReceiverRegistered = true;
    }

    private boolean isLocationEnabled(LocationManager manager) {
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void unregisterStateReceiver() {
        if (!stateReceiverRegistered) {
            return;
        }
        unregisterReceiver(stateReceiver);
        stateReceiverRegistered = false;
    }

    private TextView textView(String text, int sizeSp, int colorResource) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorResource));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(getColor(R.color.ink));
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.bg_button_secondary);
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addEqualButtons(LinearLayout row, Button... buttons) {
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    dp(42),
                    1f
            );
            if (i > 0) {
                params.leftMargin = dp(7);
            }
            row.addView(buttons[i], params);
        }
    }

    private Space weightedSpace() {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return space;
    }

    private LinearLayout.LayoutParams marginTopParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams compactSeekBarParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
        );
        params.leftMargin = -dp(8);
        params.rightMargin = -dp(8);
        return params;
    }

    private FrameLayout.LayoutParams topPanelParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        params.setMargins(dp(12), dp(12), dp(12), 0);
        return params;
    }

    private FrameLayout.LayoutParams bottomPanelParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        params.setMargins(dp(12), 0, dp(12), dp(12));
        return params;
    }

    private FrameLayout.LayoutParams attributionParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.BOTTOM | Gravity.END
        );
        params.setMargins(0, 0, dp(14), dp(430));
        return params;
    }

    private FrameLayout.LayoutParams zoomControlsParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL
        );
        params.setMargins(0, 0, dp(14), dp(70));
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
