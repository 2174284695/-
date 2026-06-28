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
import android.util.Log;
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
import com.example.routesimulator.model.RouteWaypoint;
import com.example.routesimulator.model.SavedRoute;
import com.example.routesimulator.model.WaypointType;
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
    private static final String TAG = "RouteSimulator";
    private static final String MAP_STYLE_URI =
            "https://tiles.openfreemap.org/styles/liberty";
    private static final double MIN_MAP_ZOOM = 2.0;
    private static final double MAX_MAP_ZOOM = 20.0;
    private static final int REQUEST_LOCATION_PERMISSIONS = 42;
    private static final int MAX_HISTORY = 30;
    private static final int MAX_ROUTE_WAYPOINTS = 25;
    private static final long LOCATION_TIMEOUT_MILLIS = 12_000L;
    private static final String HEALTH_WRITE_STEPS =
            "android.permission.health.WRITE_STEPS";
    private static final DateTimeFormatter ROUTE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA);
    private static final DateTimeFormatter ROUTE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA);

    private final List<RoutePoint> route = new ArrayList<>();
    private final List<RouteWaypoint> waypoints = new ArrayList<>();
    private final Deque<List<RoutePoint>> history = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService routingExecutor = Executors.newSingleThreadExecutor();
    private final RoadRouteClient roadRouteClient = new RoadRouteClient();

    private RouteStore routeStore;
    private MapView mapView;
    private MapLibreMap map;
    private RouteOverlayView routeOverlay;
    private TextView statusText;
    private TextView routeStateText;
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
    private Button routePlanButton;
    private Button waypointOrderButton;
    private Button currentStartButton;
    private Button reverseRouteButton;
    private Button moreToolsButton;
    private CheckBox roundTripCheckBox;
    private Button startButton;
    private SeekBar speedSeekBar;
    private SeekBar variationSeekBar;
    private CheckBox healthSyncCheckBox;
    private LinearLayout controlPanelContent;
    private LinearLayout moreToolsContent;
    private Button panelToggleButton;
    private boolean controlPanelCollapsed;
    private boolean moreToolsExpanded;
    private boolean mapReady;
    private boolean simulationRunning;
    private boolean pendingStartAfterPermission;
    private boolean stateReceiverRegistered;
    private boolean updatingHealthSyncCheckBox;
    private boolean mapStyleLoading;
    private boolean roadSnapInProgress;
    private String currentSavedRouteName;
    private boolean pendingLocateAfterPermission;
    private boolean pendingSetStartAfterPermission;
    private boolean locationRequestInProgress;
    private boolean locationRequestForStartPoint;
    private CancellationSignal locationCancellationSignal;
    private LocationListener legacyLocationListener;
    private Location locationFallback;
    private AlertDialog routingProgressDialog;
    private final Runnable locationTimeoutRunnable = () -> {
        if (!locationRequestInProgress) {
            return;
        }
        Location fallback = locationFallback;
        cancelLocationRequest();
        locationFallback = null;
        if (fallback != null) {
            handleLocationResult(fallback, true);
            Toast.makeText(this, "已定位到系统记录的最近位置", Toast.LENGTH_SHORT).show();
        } else {
            locationRequestForStartPoint = false;
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
                updateRouteStateText(total);
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
        waypoints.addAll(routeStore.loadWaypoints());

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
        if (routingProgressDialog != null) {
            routingProgressDialog.dismiss();
            routingProgressDialog = null;
        }
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
            boolean shouldSetStart = pendingSetStartAfterPermission;
            boolean shouldStart = pendingStartAfterPermission;
            pendingLocateAfterPermission = false;
            pendingSetStartAfterPermission = false;
            pendingStartAfterPermission = false;
            if (shouldLocate) {
                centerOnCurrentLocation();
            }
            if (shouldSetStart) {
                setCurrentLocationAsRouteStart();
            }
            if (shouldStart) {
                startSimulation();
            }
            return;
        }
        boolean locateWasPending = pendingLocateAfterPermission;
        boolean setStartWasPending = pendingSetStartAfterPermission;
        pendingLocateAfterPermission = false;
        pendingSetStartAfterPermission = false;
        pendingStartAfterPermission = false;
        Toast.makeText(
                this,
                locateWasPending || setStartWasPending
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
        routeOverlay.setWaypoints(waypoints);
        routeOverlay.setOnRouteChangedListener(previousRoute -> {
            pushHistory(previousRoute);
            waypoints.clear();
            onRouteEdited();
        });
        routeOverlay.setOnWaypointEditListener(new RouteOverlayView.OnWaypointEditListener() {
            @Override
            public void onWaypointMoved(int fromIndex, int toIndex) {
                moveWaypoint(fromIndex, toIndex);
            }

            @Override
            public void onWaypointDeleted(int index) {
                deleteWaypoint(index);
            }

            @Override
            public void onWaypointLongPressed(int index) {
                showWaypointEditDialog(index);
            }
        });
        root.addView(routeOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(buildStatusPanel(), topPanelParams());
        View zoomControls = buildZoomControls();
        View attribution = buildAttribution();
        View controlPanel = buildControlPanel();
        FrameLayout.LayoutParams zoomParams = zoomControlsParams();
        FrameLayout.LayoutParams attributionLayoutParams = attributionParams();
        root.addView(zoomControls, zoomParams);
        root.addView(attribution, attributionLayoutParams);
        root.addView(controlPanel, bottomPanelParams());
        controlPanel.addOnLayoutChangeListener((
                view,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom
        ) -> {
            int clearance = view.getHeight() + dp(24);
            if (zoomParams.bottomMargin != clearance) {
                zoomParams.bottomMargin = clearance;
                zoomControls.setLayoutParams(zoomParams);
            }
            int attributionClearance = view.getHeight() + dp(14);
            if (attributionLayoutParams.bottomMargin != attributionClearance) {
                attributionLayoutParams.bottomMargin = attributionClearance;
                attribution.setLayoutParams(attributionLayoutParams);
            }
        });
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
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundResource(R.drawable.bg_panel);
        panel.setElevation(dp(8));

        LinearLayout header = horizontalRow();
        TextView title = textView("路线控制", 18, R.color.ink);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title);
        header.addView(weightedSpace());
        panelToggleButton = secondaryButton("收起");
        panelToggleButton.setOnClickListener(view -> setControlPanelCollapsed(!controlPanelCollapsed));
        header.addView(panelToggleButton, new LinearLayout.LayoutParams(dp(78), dp(34)));
        panel.addView(header);

        LimitedHeightScrollView contentScroll = new LimitedHeightScrollView(this, 0.46f);
        contentScroll.setFillViewport(false);
        contentScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        contentScroll.setVerticalScrollBarEnabled(false);
        controlPanelContent = new LinearLayout(this);
        controlPanelContent.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(controlPanelContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        routeStateText = textView("", 14, R.color.ink);
        routeStateText.setBackgroundResource(R.drawable.bg_status_ok);
        routeStateText.setPadding(dp(10), dp(7), dp(10), dp(7));
        controlPanelContent.addView(routeStateText, marginTopParams(dp(6)));

        routeStatsText = textView("", 14, R.color.ink);
        routeStatsText.setTypeface(
                routeStatsText.getTypeface(),
                android.graphics.Typeface.BOLD
        );
        controlPanelContent.addView(routeStatsText, marginTopParams(dp(6)));

        controlPanelContent.addView(sectionLabel("编辑方式"), marginTopParams(dp(8)));
        LinearLayout modeRow = horizontalRow();
        browseButton = secondaryButton("浏览");
        pointButton = secondaryButton("点选");
        drawButton = secondaryButton("手绘");
        currentStartButton = secondaryButton("定位设起点");
        browseButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.BROWSE));
        pointButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.POINT));
        drawButton.setOnClickListener(view -> setMode(RouteOverlayView.Mode.DRAW));
        currentStartButton.setOnClickListener(view -> setCurrentLocationAsRouteStart());
        addEqualButtons(
                modeRow,
                currentStartButton,
                pointButton,
                drawButton,
                browseButton
        );
        controlPanelContent.addView(modeRow, marginTopParams(dp(4)));

        modeHintText = textView("", 13, R.color.muted);
        controlPanelContent.addView(modeHintText, marginTopParams(dp(4)));

        roundTripCheckBox = new CheckBox(this);
        roundTripCheckBox.setText("路线模式：往返（到达终点后按原路返回）");
        roundTripCheckBox.setTextSize(13);
        roundTripCheckBox.setTextColor(getColor(R.color.ink));
        roundTripCheckBox.setMinHeight(0);
        roundTripCheckBox.setPadding(0, 0, 0, 0);
        roundTripCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            routeStore.setRoundTripEnabled(isChecked);
            updateRouteStats();
        });
        controlPanelContent.addView(roundTripCheckBox, marginTopParams(dp(7)));

        LinearLayout speedHeader = horizontalRow();
        speedHeader.addView(textView("设定速度", 14, R.color.ink));
        speedHeader.addView(weightedSpace());
        speedValueText = textView("", 14, R.color.primary);
        speedHeader.addView(speedValueText);
        controlPanelContent.addView(speedHeader, marginTopParams(dp(7)));

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
        controlPanelContent.addView(speedSeekBar, compactSeekBarParams());

        controlPanelContent.addView(sectionLabel("主动作"), marginTopParams(dp(6)));
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
                dp(46)
        );
        startParams.topMargin = dp(3);
        controlPanelContent.addView(startButton, startParams);

        LinearLayout actionRow = horizontalRow();
        routePlanButton = secondaryButton("多点规划");
        routeLibraryButton = secondaryButton("路线库");
        moreToolsButton = secondaryButton("更多工具");
        routePlanButton.setOnClickListener(view -> planWalkingRoute());
        routeLibraryButton.setOnClickListener(view -> showRouteLibraryDialog());
        moreToolsButton.setOnClickListener(view -> setMoreToolsExpanded(!moreToolsExpanded));
        addEqualButtons(actionRow, routePlanButton, routeLibraryButton, moreToolsButton);
        controlPanelContent.addView(actionRow, marginTopParams(dp(5)));

        moreToolsContent = new LinearLayout(this);
        moreToolsContent.setOrientation(LinearLayout.VERTICAL);
        moreToolsContent.setVisibility(moreToolsExpanded ? View.VISIBLE : View.GONE);
        controlPanelContent.addView(moreToolsContent, marginTopParams(dp(4)));

        moreToolsContent.addView(sectionLabel("绘制与规划"), marginTopParams(dp(3)));
        LinearLayout planningToolsRow = horizontalRow();
        roadSnapButton = secondaryButton("道路吸附");
        waypointOrderButton = secondaryButton("顺序编辑");
        reverseRouteButton = secondaryButton("反转顺序");
        roadSnapButton.setOnClickListener(view -> requestHandDrawnRoadRoute(
                RoadRouteClient.Profile.FOOT,
                "路线已按步行道路吸附"
        ));
        waypointOrderButton.setOnClickListener(view -> showWaypointOrderDialog());
        reverseRouteButton.setOnClickListener(view -> reverseRouteOrder());
        addEqualButtons(planningToolsRow, roadSnapButton, waypointOrderButton, reverseRouteButton);
        moreToolsContent.addView(planningToolsRow, marginTopParams(dp(4)));

        moreToolsContent.addView(sectionLabel("路线管理"), marginTopParams(dp(7)));
        LinearLayout routeToolsRow = horizontalRow();
        saveRouteButton = secondaryButton("保存路线");
        undoButton = secondaryButton("撤销");
        clearButton = secondaryButton("清空");
        fitButton = secondaryButton("显示全程");
        saveRouteButton.setOnClickListener(view -> showSaveRouteDialog());
        undoButton.setOnClickListener(view -> undoEdit());
        clearButton.setOnClickListener(view -> clearRoute());
        fitButton.setOnClickListener(view -> fitRoute());
        addEqualButtons(routeToolsRow, saveRouteButton, undoButton, clearButton, fitButton);
        moreToolsContent.addView(routeToolsRow, marginTopParams(dp(4)));

        moreToolsContent.addView(sectionLabel("模拟高级"), marginTopParams(dp(7)));
        LinearLayout variationHeader = horizontalRow();
        variationHeader.addView(textView("自然速度波动", 14, R.color.ink));
        variationHeader.addView(weightedSpace());
        variationValueText = textView("", 14, R.color.primary);
        variationHeader.addView(variationValueText);
        moreToolsContent.addView(variationHeader, marginTopParams(dp(3)));

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
        moreToolsContent.addView(variationSeekBar, compactSeekBarParams());

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
        moreToolsContent.addView(healthSyncCheckBox, marginTopParams(dp(2)));
        updateMoreToolsButton();
        return panel;
    }

    private boolean onMapClick(LatLng point) {
        if (simulationRunning || routeOverlay.getMode() != RouteOverlayView.Mode.POINT) {
            return false;
        }
        if (waypoints.size() >= MAX_ROUTE_WAYPOINTS) {
            Toast.makeText(this, "最多选择 25 个途经点", Toast.LENGTH_SHORT).show();
            return true;
        }
        pushHistory(new ArrayList<>(route));
        RoutePoint routePoint = new RoutePoint(point.getLatitude(), point.getLongitude());
        waypoints.add(RouteWaypoint.auto(routePoint));
        route.clear();
        route.addAll(waypointPoints());
        onRouteEdited();
        return true;
    }

    private void moveWaypoint(int fromIndex, int toIndex) {
        if (simulationRunning
                || fromIndex < 0
                || toIndex < 0
                || fromIndex >= waypoints.size()
                || toIndex >= waypoints.size()
                || fromIndex == toIndex) {
            return;
        }
        pushHistory(new ArrayList<>(route));
        RouteWaypoint moved = waypoints.remove(fromIndex);
        waypoints.add(toIndex, moved);
        route.clear();
        route.addAll(waypointPoints());
        onRouteEdited();
        Toast.makeText(this, "已调整途经点顺序", Toast.LENGTH_SHORT).show();
    }

    private void deleteWaypoint(int index) {
        if (simulationRunning || index < 0 || index >= waypoints.size()) {
            return;
        }
        pushHistory(new ArrayList<>(route));
        waypoints.remove(index);
        route.clear();
        route.addAll(waypointPoints());
        onRouteEdited();
        Toast.makeText(this, "已删除途经点", Toast.LENGTH_SHORT).show();
    }

    private void showWaypointEditDialog(int index) {
        if (simulationRunning || index < 0 || index >= waypoints.size()) {
            return;
        }
        RouteWaypoint waypoint = waypoints.get(index);
        String[] actions = new String[]{
                "设为道路点（蓝色，允许贴合道路）",
                "设为强制经过点（橙色，保留原始坐标）",
                "删除这个途经点"
        };
        new AlertDialog.Builder(this)
                .setTitle("途经点 " + (index + 1))
                .setMessage("当前：" + waypointTypeLabel(waypoint))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        setWaypointType(index, WaypointType.ROAD);
                    } else if (which == 1) {
                        setWaypointType(index, WaypointType.FORCED);
                    } else {
                        deleteWaypoint(index);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setWaypointType(int index, WaypointType type) {
        if (simulationRunning || index < 0 || index >= waypoints.size()) {
            return;
        }
        RouteWaypoint current = waypoints.get(index);
        if (current.type() == type && current.isManualType()) {
            Toast.makeText(this, "途经点类型未变化", Toast.LENGTH_SHORT).show();
            return;
        }
        waypoints.set(index, current.withManualType(type));
        onRouteEdited();
        Toast.makeText(
                this,
                type == WaypointType.FORCED
                        ? "已设为强制经过点；下次规划会保留该坐标"
                        : "已设为道路点；下次规划会优先贴合道路",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showWaypointOrderDialog() {
        if (waypoints.size() < 2) {
            Toast.makeText(this, "请先点选至少两个途经点", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(8));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(list);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑途经点顺序")
                .setMessage("点上的数字就是通过顺序。蓝色为道路点，橙色为强制经过点。")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .create();

        for (int i = 0; i < waypoints.size(); i++) {
            RouteWaypoint waypoint = waypoints.get(i);
            RoutePoint point = waypoint.point();
            LinearLayout row = horizontalRow();
            row.setPadding(0, dp(4), 0, dp(4));

            TextView details = textView(String.format(
                    Locale.CHINA,
                    "%02d  %s\n%.5f, %.5f",
                    i + 1,
                    waypointTypeLabel(waypoint),
                    point.latitude(),
                    point.longitude()
            ), 13, R.color.ink);
            row.addView(details, new LinearLayout.LayoutParams(0, dp(54), 1f));

            Button up = secondaryButton("上移");
            up.setEnabled(i > 0);
            final int upIndex = i;
            up.setOnClickListener(view -> {
                dialog.dismiss();
                moveWaypoint(upIndex, upIndex - 1);
                showWaypointOrderDialog();
            });
            LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(dp(56), dp(38));
            upParams.leftMargin = dp(6);
            row.addView(up, upParams);

            Button down = secondaryButton("下移");
            down.setEnabled(i < waypoints.size() - 1);
            final int downIndex = i;
            down.setOnClickListener(view -> {
                dialog.dismiss();
                moveWaypoint(downIndex, downIndex + 1);
                showWaypointOrderDialog();
            });
            LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(56), dp(38));
            downParams.leftMargin = dp(6);
            row.addView(down, downParams);

            list.addView(row);
        }
        dialog.show();
    }

    private String waypointTypeLabel(RouteWaypoint waypoint) {
        String type = waypoint.type() == WaypointType.FORCED ? "强制经过点" : "道路点";
        return waypoint.isManualType() ? type + "（手动）" : type + "（自动）";
    }

    private void reverseRouteOrder() {
        if (simulationRunning) {
            return;
        }
        if (route.size() < 2) {
            Toast.makeText(this, "至少需要两个路线点才能反转", Toast.LENGTH_SHORT).show();
            return;
        }
        pushHistory(new ArrayList<>(route));
        Collections.reverse(route);
        if (waypoints.size() >= 2) {
            Collections.reverse(waypoints);
        }
        onRouteEdited();
        Toast.makeText(this, "已对调起点和终点顺序", Toast.LENGTH_SHORT).show();
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
            modeHintText.setText("点按添加途经点，拖动调序，长按可改类型或删除");
        } else if (mode == RouteOverlayView.Mode.DRAW) {
            modeHintText.setText("连续手绘大致轨迹，完成后点“道路吸附”");
        } else {
            modeHintText.setText("拖动地图浏览，双指缩放");
        }
    }

    private void onRouteEdited() {
        currentSavedRouteName = null;
        routeStore.saveRoute(route);
        routeStore.saveWaypoints(waypoints);
        routeOverlay.setActivePoint(null);
        routeOverlay.setFailedRoute(Collections.emptyList());
        routeOverlay.setWaypoints(waypoints);
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
        boolean undoingWaypoint = route.equals(waypointPoints()) && !waypoints.isEmpty();
        route.clear();
        route.addAll(history.removeLast());
        if (undoingWaypoint) {
            waypoints.remove(waypoints.size() - 1);
        }
        onRouteEdited();
    }

    private void clearRoute() {
        if (route.isEmpty()) {
            return;
        }
        pushHistory(new ArrayList<>(route));
        route.clear();
        waypoints.clear();
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
                    SavedRoute savedRoute = routeStore.saveNamedRoute(name, route, waypoints);
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
                    "%s\n%.2f km · %d 点 · %d 途经点 · %s",
                    savedRoute.name(),
                    savedRoute.distanceMeters() / 1000.0,
                    savedRoute.points().size(),
                    savedRoute.waypoints().size(),
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
        loadSavedRouteWithoutToast(savedRoute);
        Toast.makeText(this, "已载入“" + savedRoute.name() + "”", Toast.LENGTH_SHORT).show();
    }

    private void loadSavedRouteWithoutToast(SavedRoute savedRoute) {
        route.clear();
        route.addAll(savedRoute.points());
        waypoints.clear();
        waypoints.addAll(savedRoute.waypoints());
        currentSavedRouteName = savedRoute.name();
        routeStore.saveRoute(route);
        routeStore.saveWaypoints(waypoints);
        routeOverlay.setActivePoint(null);
        routeOverlay.setWaypoints(waypoints);
        routeOverlay.invalidate();
        updateRouteStats();
        setMode(RouteOverlayView.Mode.BROWSE);
        mapView.post(this::fitRoute);
    }

    private void requestHandDrawnRoadRoute(
            RoadRouteClient.Profile profile,
            String successMessage
    ) {
        if (route.size() < 2) {
            List<SavedRoute> savedRoutes = routeStore.loadSavedRoutes();
            if (!savedRoutes.isEmpty()) {
                SavedRoute latestRoute = savedRoutes.get(0);
                new AlertDialog.Builder(this)
                        .setTitle("当前没有可吸附路线")
                        .setMessage(
                                "路线库中有已保存路线“" + latestRoute.name()
                                        + "”。是否先载入它，再进行道路吸附？"
                        )
                        .setNegativeButton("取消", null)
                        .setPositiveButton("载入并吸附", (dialog, which) -> {
                            loadSavedRouteWithoutToast(latestRoute);
                            requestHandDrawnRoadRoute(profile, successMessage);
                        })
                        .show();
                return;
            }
            Toast.makeText(this, "请先手绘或从路线库载入一条路线", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始道路吸附…", Toast.LENGTH_SHORT).show();
        requestRoadRoute(new ArrayList<>(route), profile, successMessage);
    }

    private void planWalkingRoute() {
        List<RouteWaypoint> requestedWaypoints = chooseRoutePlanningWaypoints();
        if (requestedWaypoints.size() < 2) {
            Toast.makeText(
                    this,
                    "请先点选至少两个途经点，或载入一条已有路线",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        Toast.makeText(this, "开始多点步行规划…", Toast.LENGTH_SHORT).show();
        requestWaypointRoadRoute(
                requestedWaypoints,
                RoadRouteClient.Profile.FOOT,
                "已生成步行道路路线"
        );
    }

    private List<RouteWaypoint> chooseRoutePlanningWaypoints() {
        if (waypoints.size() >= 2) {
            return new ArrayList<>(waypoints);
        }
        if (route.size() >= 2) {
            List<RouteWaypoint> routeWaypoints = new ArrayList<>(route.size());
            for (RoutePoint point : route) {
                routeWaypoints.add(RouteWaypoint.auto(point));
            }
            return routeWaypoints;
        }
        return Collections.emptyList();
    }

    private void setControlPanelCollapsed(boolean collapsed) {
        controlPanelCollapsed = collapsed;
        if (controlPanelContent != null) {
            controlPanelContent.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (panelToggleButton != null) {
            panelToggleButton.setText(collapsed ? "展开" : "收起");
        }
    }

    private void setMoreToolsExpanded(boolean expanded) {
        moreToolsExpanded = expanded;
        if (moreToolsContent != null) {
            moreToolsContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        updateMoreToolsButton();
    }

    private void updateMoreToolsButton() {
        if (moreToolsButton != null) {
            moreToolsButton.setText(moreToolsExpanded ? "收起工具" : "更多工具");
        }
    }

    private void requestRoadRoute(
            List<RoutePoint> requestedPoints,
            RoadRouteClient.Profile profile,
            String successMessage
    ) {
        if (roadSnapInProgress || simulationRunning) {
            Toast.makeText(this, "路线正在规划，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        List<RoutePoint> original = new ArrayList<>(route);
        List<RoutePoint> requestSnapshot = new ArrayList<>(requestedPoints);
        roadSnapInProgress = true;
        roadSnapButton.setText("规划中…");
        routePlanButton.setText("规划中…");
        routeOverlay.setFailedRoute(Collections.emptyList());
        setEditorEnabled(false);
        startButton.setEnabled(false);
        routingProgressDialog = new AlertDialog.Builder(this)
                .setTitle("正在规划道路路线")
                .setMessage(
                        "正在连接主道路服务；如果主服务失败，会自动切换备用服务。"
                                + "\n复杂手绘路线可能需要几十秒。"
                )
                .setCancelable(false)
                .create();
        routingProgressDialog.show();

        routingExecutor.execute(() -> {
            try {
                Log.i(TAG, "road routing request started, profile=" + profile
                        + ", requestedPoints=" + requestSnapshot.size());
                List<RoutePoint> snapped = roadRouteClient.route(requestSnapshot, profile);
                Log.i(TAG, "road routing request succeeded, resultPoints=" + snapped.size());
                mainHandler.post(() -> completeRoadRoute(
                        original,
                        requestSnapshot,
                        profile,
                        snapped,
                        successMessage,
                        null
                ));
            } catch (Exception exception) {
                Log.e(TAG, "road routing request failed", exception);
                mainHandler.post(() -> completeRoadRoute(
                        original,
                        requestSnapshot,
                        profile,
                        null,
                        successMessage,
                        exception
                ));
            }
        });
    }

    private void requestWaypointRoadRoute(
            List<RouteWaypoint> requestedWaypoints,
            RoadRouteClient.Profile profile,
            String successMessage
    ) {
        if (roadSnapInProgress || simulationRunning) {
            Toast.makeText(this, "路线正在规划，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        List<RoutePoint> original = new ArrayList<>(route);
        List<RouteWaypoint> requestSnapshot = new ArrayList<>(requestedWaypoints);
        List<RoutePoint> requestPoints = RouteWaypoint.pointsOf(requestSnapshot);
        roadSnapInProgress = true;
        roadSnapButton.setText("规划中…");
        routePlanButton.setText("规划中…");
        routeOverlay.setFailedRoute(Collections.emptyList());
        setEditorEnabled(false);
        startButton.setEnabled(false);
        routingProgressDialog = new AlertDialog.Builder(this)
                .setTitle("正在规划混合路线")
                .setMessage(
                        "道路点会贴合步行道路；远离道路的点会作为强制经过点保留。"
                                + "\n复杂路线可能需要几十秒。"
                )
                .setCancelable(false)
                .create();
        routingProgressDialog.show();

        routingExecutor.execute(() -> {
            try {
                Log.i(TAG, "hybrid routing request started, profile=" + profile
                        + ", requestedWaypoints=" + requestSnapshot.size());
                RoadRouteClient.HybridRouteResult result = roadRouteClient.routeWaypoints(
                        requestSnapshot,
                        profile
                );
                Log.i(TAG, "hybrid routing request succeeded, resultPoints="
                        + result.route().size());
                mainHandler.post(() -> completeWaypointRoadRoute(
                        original,
                        requestSnapshot,
                        profile,
                        result,
                        successMessage,
                        null
                ));
            } catch (Exception exception) {
                Log.e(TAG, "hybrid routing request failed", exception);
                mainHandler.post(() -> completeWaypointRoadRoute(
                        original,
                        requestSnapshot,
                        profile,
                        null,
                        successMessage,
                        exception
                ));
            }
        });
    }

    private void completeRoadRoute(
            List<RoutePoint> original,
            List<RoutePoint> requestedPoints,
            RoadRouteClient.Profile profile,
            List<RoutePoint> snapped,
            String successMessage,
            Exception error
    ) {
        if (isDestroyed()) {
            return;
        }
        roadSnapInProgress = false;
        if (routingProgressDialog != null) {
            routingProgressDialog.dismiss();
            routingProgressDialog = null;
        }
        roadSnapButton.setText("道路吸附");
        routePlanButton.setText("多点规划");
        startButton.setEnabled(true);
        setEditorEnabled(!simulationRunning);

        if (error != null || snapped == null) {
            routeOverlay.setFailedRoute(requestedPoints);
            showRoadRouteFailureDialog(original, requestedPoints, profile, successMessage, error);
            return;
        }

        pushHistory(original);
        route.clear();
        route.addAll(snapped);
        onRouteEdited();
        setMode(RouteOverlayView.Mode.BROWSE);
        mapView.post(this::fitRoute);
        Toast.makeText(
                this,
                successMessage + "，可使用撤销恢复",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void completeWaypointRoadRoute(
            List<RoutePoint> original,
            List<RouteWaypoint> requestedWaypoints,
            RoadRouteClient.Profile profile,
            RoadRouteClient.HybridRouteResult result,
            String successMessage,
            Exception error
    ) {
        if (isDestroyed()) {
            return;
        }
        roadSnapInProgress = false;
        if (routingProgressDialog != null) {
            routingProgressDialog.dismiss();
            routingProgressDialog = null;
        }
        roadSnapButton.setText("道路吸附");
        routePlanButton.setText("多点规划");
        startButton.setEnabled(true);
        setEditorEnabled(!simulationRunning);

        List<RoutePoint> requestPoints = RouteWaypoint.pointsOf(requestedWaypoints);
        if (error != null || result == null) {
            routeOverlay.setFailedRoute(requestPoints);
            showWaypointRouteFailureDialog(
                    original,
                    requestedWaypoints,
                    profile,
                    successMessage,
                    error
            );
            return;
        }

        pushHistory(original);
        route.clear();
        route.addAll(result.route());
        waypoints.clear();
        waypoints.addAll(result.waypoints());
        onRouteEdited();
        setMode(RouteOverlayView.Mode.BROWSE);
        mapView.post(this::fitRoute);
        Toast.makeText(
                this,
                successMessage + "，强制经过点已保留，可使用撤销恢复",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showRoadRouteFailureDialog(
            List<RoutePoint> original,
            List<RoutePoint> requestedPoints,
            RoadRouteClient.Profile profile,
            String successMessage,
            Exception error
    ) {
        String detail = error == null ? "" : error.getMessage();
        String message = detail == null || detail.isEmpty()
                ? "没有收到道路路线，请检查网络后重试。"
                : detail;
        new AlertDialog.Builder(this)
                .setTitle("道路规划失败")
                .setMessage(message + "\n\n红色线段已标出本次失败的规划范围。")
                .setPositiveButton("重新规划", (dialog, which) -> requestRoadRoute(
                        new ArrayList<>(requestedPoints),
                        profile,
                        successMessage
                ))
                .setNegativeButton("保留原路线", (dialog, which) -> {
                    routeOverlay.setFailedRoute(Collections.emptyList());
                    Toast.makeText(this, "已保留当前路线", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("撤销规划", (dialog, which) -> restoreRouteAfterFailedPlanning(original))
                .show();
    }

    private void showWaypointRouteFailureDialog(
            List<RoutePoint> original,
            List<RouteWaypoint> requestedWaypoints,
            RoadRouteClient.Profile profile,
            String successMessage,
            Exception error
    ) {
        String detail = error == null ? "" : error.getMessage();
        String message = detail == null || detail.isEmpty()
                ? "没有收到混合路线，请检查网络后重试。"
                : detail;
        new AlertDialog.Builder(this)
                .setTitle("混合路线规划失败")
                .setMessage(message + "\n\n红色线段已标出本次失败的规划范围。")
                .setPositiveButton("重新规划", (dialog, which) -> requestWaypointRoadRoute(
                        new ArrayList<>(requestedWaypoints),
                        profile,
                        successMessage
                ))
                .setNegativeButton("保留原路线", (dialog, which) -> {
                    routeOverlay.setFailedRoute(Collections.emptyList());
                    Toast.makeText(this, "已保留当前路线", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("撤销规划", (dialog, which) -> restoreRouteAfterFailedPlanning(original))
                .show();
    }

    private void restoreRouteAfterFailedPlanning(List<RoutePoint> original) {
        route.clear();
        route.addAll(original);
        if (!waypoints.isEmpty() && !route.equals(waypointPoints())) {
            waypoints.clear();
        }
        routeStore.saveRoute(route);
        routeStore.saveWaypoints(waypoints);
        routeOverlay.setActivePoint(null);
        routeOverlay.setFailedRoute(Collections.emptyList());
        routeOverlay.setWaypoints(waypoints);
        routeOverlay.invalidate();
        updateRouteStats();
        updateEditButtons();
        Toast.makeText(this, "已撤销本次规划，恢复原路线", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("MissingPermission")
    private void centerOnCurrentLocation() {
        requestCurrentLocation(false);
    }

    @SuppressLint("MissingPermission")
    private void setCurrentLocationAsRouteStart() {
        requestCurrentLocation(true);
    }

    @SuppressLint("MissingPermission")
    private void requestCurrentLocation(boolean useAsRouteStart) {
        if (simulationRunning) {
            Toast.makeText(this, "请先停止模拟，再获取真实当前位置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocateAfterPermission = !useAsRouteStart;
            pendingSetStartAfterPermission = useAsRouteStart;
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION_PERMISSIONS
            );
            return;
        }
        if (!useAsRouteStart && (!mapReady || map == null)) {
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
            locationRequestForStartPoint = useAsRouteStart;
            handleLocationResult(lastKnown, false);
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
        locationRequestForStartPoint = useAsRouteStart;
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
            handleLocationResult(location, false);
        } else if (fallback != null) {
            handleLocationResult(fallback, true);
            Toast.makeText(this, "已定位到系统记录的最近位置", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "未能获取当前位置", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleLocationResult(Location location, boolean fromFallback) {
        if (locationRequestForStartPoint) {
            addLocationAsRouteStart(location, fromFallback);
        } else {
            showLocationOnMap(location);
        }
        locationRequestForStartPoint = false;
    }

    private void addLocationAsRouteStart(Location location, boolean fromFallback) {
        RoutePoint start = new RoutePoint(location.getLatitude(), location.getLongitude());
        pushHistory(new ArrayList<>(route));
        if (!waypoints.isEmpty()) {
            if (!samePlace(start, waypoints.get(0).point())) {
                waypoints.add(0, RouteWaypoint.auto(start));
            } else {
                waypoints.set(0, waypoints.get(0).withPoint(start));
            }
            route.clear();
            route.addAll(waypointPoints());
        } else if (!route.isEmpty()) {
            if (!samePlace(start, route.get(0))) {
                route.add(0, start);
            } else {
                route.set(0, start);
            }
        } else {
            waypoints.add(RouteWaypoint.auto(start));
            route.add(start);
            setMode(RouteOverlayView.Mode.POINT);
        }
        onRouteEdited();
        if (mapReady && map != null) {
            map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                            new LatLng(start.latitude(), start.longitude()),
                            17.0
                    ),
                    650
            );
        }
        Toast.makeText(
                this,
                fromFallback ? "已用最近位置设为起点" : "已将当前位置设为起点",
                Toast.LENGTH_SHORT
        ).show();
    }

    private boolean samePlace(RoutePoint a, RoutePoint b) {
        return GeoMath.distanceMeters(a, b) < 2.0;
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
        routeStore.setRoundTripEnabled(roundTripCheckBox != null && roundTripCheckBox.isChecked());
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
        roadSnapButton.setEnabled(enabled && !roadSnapInProgress);
        routePlanButton.setEnabled(enabled && !roadSnapInProgress);
        waypointOrderButton.setEnabled(enabled && waypoints.size() >= 2);
        currentStartButton.setEnabled(enabled);
        reverseRouteButton.setEnabled(enabled && route.size() >= 2);
        roundTripCheckBox.setEnabled(enabled);
        speedSeekBar.setEnabled(enabled);
        variationSeekBar.setEnabled(enabled);
        healthSyncCheckBox.setEnabled(enabled && isHealthSyncAvailable());
        if (moreToolsButton != null) {
            moreToolsButton.setEnabled(enabled);
        }
    }

    private void updateEditButtons() {
        undoButton.setEnabled(!simulationRunning && !history.isEmpty());
        clearButton.setEnabled(!simulationRunning && !route.isEmpty());
        fitButton.setEnabled(!route.isEmpty());
        saveRouteButton.setEnabled(!simulationRunning && route.size() >= 2);
        routeLibraryButton.setEnabled(!simulationRunning);
        roadSnapButton.setEnabled(!simulationRunning && !roadSnapInProgress);
        routePlanButton.setEnabled(!simulationRunning && !roadSnapInProgress);
        waypointOrderButton.setEnabled(!simulationRunning && waypoints.size() >= 2);
        currentStartButton.setEnabled(!simulationRunning);
        reverseRouteButton.setEnabled(!simulationRunning && route.size() >= 2);
        roundTripCheckBox.setEnabled(!simulationRunning);
        startButton.setEnabled(simulationRunning || (!roadSnapInProgress && route.size() >= 2));
        moreToolsButton.setEnabled(!simulationRunning);
        routeLibraryButton.setText(String.format(
                Locale.CHINA,
                "路线库 (%d)",
                routeStore.loadSavedRoutes().size()
        ));
        routePlanButton.setText(roadSnapInProgress ? "规划中…" : "多点规划");
        roadSnapButton.setText(roadSnapInProgress ? "规划中…" : "道路吸附");
        updateMoreToolsButton();
    }

    private void updateRouteStats() {
        double meters = GeoMath.totalDistanceMeters(routeForStats());
        float speedKmh = selectedSpeedKmh();
        double minutes = speedKmh <= 0f ? 0.0 : (meters / 1000.0) / speedKmh * 60.0;
        long estimatedSteps = Math.round(
                meters / SimulatedStepModel.estimateStrideMeters(speedKmh / 3.6)
        );
        String waypointSummary = waypoints.isEmpty()
                ? ""
                : String.format(Locale.CHINA, " · %d 途经点", waypoints.size());
        routeStatsText.setText(String.format(
                Locale.CHINA,
                "%d 个点%s · %.2f km · 约 %.0f 分钟 / %,d 步",
                route.size(),
                waypointSummary,
                meters / 1000.0,
                minutes,
                estimatedSteps
        ));
        updateRouteStateText(meters);
        updateEditButtons();
    }

    private void updateRouteStateText(double metersForStats) {
        if (routeStateText == null) {
            return;
        }
        String message;
        boolean warning = false;
        if (simulationRunning) {
            message = "当前状态：正在模拟。下一步：可从主按钮停止，或收起面板查看地图。";
        } else if (roadSnapInProgress) {
            message = "当前状态：正在规划路线。下一步：等待结果，失败时可重新规划/保留/撤销。";
        } else if (route.isEmpty()) {
            message = "当前状态：空路线。下一步：定位设起点并点选、手绘路线，或从路线库载入。";
            warning = true;
        } else if (route.size() == 1 || metersForStats < 1.0) {
            message = "当前状态：路线过短。下一步：继续点选或手绘补足至少两个有效点。";
            warning = true;
        } else if (!waypoints.isEmpty()) {
            message = "当前状态：混合路线。下一步：长按途经点改类型，或多点规划后开始模拟。";
        } else {
            message = "当前状态：已有路线。下一步：开始模拟、重新规划，或保存到路线库。";
        }
        routeStateText.setText(message);
        routeStateText.setBackgroundResource(warning
                ? R.drawable.bg_status_warn
                : R.drawable.bg_status_ok);
        routeStateText.setPadding(dp(10), dp(7), dp(10), dp(7));
    }

    private List<RoutePoint> routeForStats() {
        if (roundTripCheckBox != null && roundTripCheckBox.isChecked() && route.size() >= 2) {
            return buildRoundTripRoute(route);
        }
        return route;
    }

    private List<RoutePoint> waypointPoints() {
        return RouteWaypoint.pointsOf(waypoints);
    }

    private static List<RoutePoint> buildRoundTripRoute(List<RoutePoint> oneWayRoute) {
        List<RoutePoint> roundTrip = new ArrayList<>(oneWayRoute);
        for (int i = oneWayRoute.size() - 2; i >= 0; i--) {
            roundTrip.add(oneWayRoute.get(i));
        }
        return roundTrip;
    }

    private void loadSavedSettings() {
        float speed = routeStore.loadSpeedKmh();
        int variation = routeStore.loadVariationPercent();
        speedSeekBar.setProgress(Math.round((speed - 1.0f) * 10f));
        variationSeekBar.setProgress(Math.max(0, variation - 1));
        roundTripCheckBox.setChecked(routeStore.isRoundTripEnabled());
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
        button.setTextSize(12);
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

    private TextView sectionLabel(String text) {
        TextView label = textView(text, 13, R.color.muted);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        return label;
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
                    dp(38),
                    1f
            );
            if (i > 0) {
                params.leftMargin = dp(6);
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
                dp(30)
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
        params.setMargins(0, 0, dp(14), dp(14));
        return params;
    }

    private FrameLayout.LayoutParams zoomControlsParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM
        );
        params.setMargins(0, 0, dp(14), dp(24));
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LimitedHeightScrollView extends ScrollView {
        private final float maxHeightFraction;

        LimitedHeightScrollView(Context context, float maxHeightFraction) {
            super(context);
            this.maxHeightFraction = maxHeightFraction;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int maxHeight = Math.round(screenHeight * maxHeightFraction);
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);
            int cappedHeight = heightMode == MeasureSpec.UNSPECIFIED
                    ? maxHeight
                    : Math.min(heightSize, maxHeight);
            int cappedHeightSpec = MeasureSpec.makeMeasureSpec(
                    cappedHeight,
                    MeasureSpec.AT_MOST
            );
            super.onMeasure(widthMeasureSpec, cappedHeightSpec);
        }
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
