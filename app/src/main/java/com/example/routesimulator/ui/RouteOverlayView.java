package com.example.routesimulator.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.example.routesimulator.model.RouteDraftMerger;
import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.RouteSegmentClassifier;
import com.example.routesimulator.model.RouteWaypoint;
import com.example.routesimulator.model.RouteSimplifier;
import com.example.routesimulator.model.WaypointType;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RouteOverlayView extends View {
    public enum Mode {
        BROWSE,
        POINT,
        DRAW
    }

    public interface OnRouteChangedListener {
        void onRouteChanged(List<RoutePoint> previousRoute);
    }

    public interface OnWaypointEditListener {
        void onWaypointMoved(int fromIndex, int toIndex);

        void onWaypointDeleted(int index);

        void onWaypointLongPressed(int index);
    }

    private final Paint routeOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offRoadSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint draftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint failurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waypointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint forcedWaypointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waypointOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waypointDragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waypointNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PointF> draftPoints = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MapLibreMap map;
    private List<RoutePoint> route = Collections.emptyList();
    private List<RouteWaypoint> waypoints = Collections.emptyList();
    private List<RoutePoint> failedRoute = Collections.emptyList();
    private Mode mode = Mode.BROWSE;
    private RoutePoint activePoint;
    private OnRouteChangedListener routeChangedListener;
    private OnWaypointEditListener waypointEditListener;
    private float minimumDraftSpacing;
    private float touchSlop;
    private int draggingWaypointIndex = -1;
    private int pressedWaypointIndex = -1;
    private boolean draggingWaypoint;
    private boolean longPressHandled;
    private float downX;
    private float downY;
    private final Runnable longPressRunnable = () -> {
        if (mode == Mode.POINT
                && pressedWaypointIndex >= 0
                && !draggingWaypoint
                && waypointEditListener != null) {
            longPressHandled = true;
            waypointEditListener.onWaypointLongPressed(pressedWaypointIndex);
            pressedWaypointIndex = -1;
            performClick();
        }
    };

    public RouteOverlayView(Context context) {
        this(context, null);
    }

    public RouteOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        minimumDraftSpacing = 8f * getResources().getDisplayMetrics().density;
        touchSlop = 6f * getResources().getDisplayMetrics().density;

        routeOutlinePaint.setColor(Color.WHITE);
        routeOutlinePaint.setStrokeWidth(dp(9));
        routeOutlinePaint.setStyle(Paint.Style.STROKE);
        routeOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
        routeOutlinePaint.setStrokeJoin(Paint.Join.ROUND);

        routePaint.setColor(Color.rgb(19, 122, 80));
        routePaint.setStrokeWidth(dp(5));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        offRoadSegmentPaint.setColor(Color.rgb(255, 138, 0));
        offRoadSegmentPaint.setAlpha(235);
        offRoadSegmentPaint.setStrokeWidth(dp(7));
        offRoadSegmentPaint.setStyle(Paint.Style.STROKE);
        offRoadSegmentPaint.setStrokeCap(Paint.Cap.ROUND);
        offRoadSegmentPaint.setStrokeJoin(Paint.Join.ROUND);
        offRoadSegmentPaint.setPathEffect(new DashPathEffect(
                new float[]{dp(10), dp(7)},
                0f
        ));

        draftPaint.setColor(Color.rgb(28, 146, 97));
        draftPaint.setAlpha(210);
        draftPaint.setStrokeWidth(dp(5));
        draftPaint.setStyle(Paint.Style.STROKE);
        draftPaint.setStrokeCap(Paint.Cap.ROUND);
        draftPaint.setStrokeJoin(Paint.Join.ROUND);

        failurePaint.setColor(Color.rgb(210, 52, 48));
        failurePaint.setAlpha(230);
        failurePaint.setStrokeWidth(dp(6));
        failurePaint.setStyle(Paint.Style.STROKE);
        failurePaint.setStrokeCap(Paint.Cap.ROUND);
        failurePaint.setStrokeJoin(Paint.Join.ROUND);

        markerPaint.setColor(Color.WHITE);
        markerPaint.setStyle(Paint.Style.FILL);
        markerOutlinePaint.setColor(Color.rgb(19, 122, 80));
        markerOutlinePaint.setStrokeWidth(dp(3));
        markerOutlinePaint.setStyle(Paint.Style.STROKE);

        activePaint.setColor(Color.rgb(255, 138, 0));
        activePaint.setStyle(Paint.Style.FILL);
        waypointPaint.setColor(Color.rgb(31, 92, 190));
        waypointPaint.setStyle(Paint.Style.FILL);
        forcedWaypointPaint.setColor(Color.rgb(255, 138, 0));
        forcedWaypointPaint.setStyle(Paint.Style.FILL);
        waypointOutlinePaint.setColor(Color.WHITE);
        waypointOutlinePaint.setStrokeWidth(dp(3));
        waypointOutlinePaint.setStyle(Paint.Style.STROKE);
        waypointDragPaint.setColor(Color.rgb(255, 138, 0));
        waypointDragPaint.setStyle(Paint.Style.FILL);
        waypointNumberPaint.setColor(Color.WHITE);
        waypointNumberPaint.setTextAlign(Paint.Align.CENTER);
        waypointNumberPaint.setTypeface(Typeface.DEFAULT_BOLD);
        setMode(Mode.BROWSE);
    }

    public void setMap(MapLibreMap map) {
        this.map = map;
        map.addOnCameraMoveListener(this::invalidate);
        map.addOnCameraIdleListener(this::invalidate);
        invalidate();
    }

    public void setRoute(List<RoutePoint> route) {
        this.route = route;
        invalidate();
    }

    public void setWaypoints(List<RouteWaypoint> waypoints) {
        this.waypoints = waypoints;
        invalidate();
    }

    public void setFailedRoute(List<RoutePoint> failedRoute) {
        this.failedRoute = failedRoute == null ? Collections.emptyList() : failedRoute;
        invalidate();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        boolean handlesTouches = mode == Mode.DRAW || mode == Mode.POINT;
        setClickable(handlesTouches);
        setFocusable(handlesTouches);
        resetWaypointGesture();
        draftPoints.clear();
        invalidate();
    }

    public Mode getMode() {
        return mode;
    }

    public void setActivePoint(RoutePoint activePoint) {
        this.activePoint = activePoint;
        invalidate();
    }

    public void setOnRouteChangedListener(OnRouteChangedListener listener) {
        routeChangedListener = listener;
    }

    public void setOnWaypointEditListener(OnWaypointEditListener listener) {
        waypointEditListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) {
            return;
        }
        drawRoute(canvas);
        drawFailedRoute(canvas);
        drawWaypoints(canvas);
        drawDraft(canvas);
        drawActivePoint(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (map == null) {
            return false;
        }
        if (mode == Mode.POINT) {
            return handleWaypointTouch(event);
        }
        if (mode != Mode.DRAW) {
            return false;
        }

        PointF current = new PointF(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draftPoints.clear();
                draftPoints.add(current);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                appendDraftPoint(current);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                appendDraftPoint(current);
                commitDraft();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                draftPoints.clear();
                resetWaypointGesture();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawRoute(Canvas canvas) {
        if (route.isEmpty()) {
            return;
        }
        Path path = new Path();
        for (int i = 0; i < route.size(); i++) {
            PointF screenPoint = toScreenPoint(route.get(i));
            if (i == 0) {
                path.moveTo(screenPoint.x, screenPoint.y);
            } else {
                path.lineTo(screenPoint.x, screenPoint.y);
            }
        }
        if (route.size() > 1) {
            canvas.drawPath(path, routeOutlinePaint);
            canvas.drawPath(path, routePaint);
            drawOffRoadSegments(canvas);
        }

        PointF start = toScreenPoint(route.get(0));
        drawMarker(canvas, start, Color.rgb(19, 122, 80));
        if (route.size() > 1) {
            PointF end = toScreenPoint(route.get(route.size() - 1));
            drawMarker(canvas, end, Color.rgb(179, 38, 30));
        }
    }

    private void drawDraft(Canvas canvas) {
        if (draftPoints.size() < 2) {
            return;
        }
        Path path = new Path();
        path.moveTo(draftPoints.get(0).x, draftPoints.get(0).y);
        for (int i = 1; i < draftPoints.size(); i++) {
            path.lineTo(draftPoints.get(i).x, draftPoints.get(i).y);
        }
        canvas.drawPath(path, draftPaint);
    }

    private void drawFailedRoute(Canvas canvas) {
        if (failedRoute.size() < 2) {
            return;
        }
        Path path = new Path();
        for (int i = 0; i < failedRoute.size(); i++) {
            PointF screenPoint = toScreenPoint(failedRoute.get(i));
            if (i == 0) {
                path.moveTo(screenPoint.x, screenPoint.y);
            } else {
                path.lineTo(screenPoint.x, screenPoint.y);
            }
        }
        canvas.drawPath(path, failurePaint);
    }

    private void drawOffRoadSegments(Canvas canvas) {
        boolean[] offRoadSegments = RouteSegmentClassifier.offRoadSegmentsForForcedWaypoints(
                route,
                waypoints
        );
        for (int i = 0; i < offRoadSegments.length; i++) {
            if (!offRoadSegments[i]) {
                continue;
            }
            PointF start = toScreenPoint(route.get(i));
            PointF end = toScreenPoint(route.get(i + 1));
            Path path = new Path();
            path.moveTo(start.x, start.y);
            path.lineTo(end.x, end.y);
            canvas.drawPath(path, offRoadSegmentPaint);
        }
    }

    private void drawActivePoint(Canvas canvas) {
        if (activePoint == null) {
            return;
        }
        PointF point = toScreenPoint(activePoint);
        canvas.drawCircle(point.x, point.y, dp(10), markerPaint);
        canvas.drawCircle(point.x, point.y, dp(7), activePaint);
    }

    private void drawWaypoints(Canvas canvas) {
        for (int i = 0; i < waypoints.size(); i++) {
            RouteWaypoint waypoint = waypoints.get(i);
            PointF point = toScreenPoint(waypoint.point());
            float radius = i == draggingWaypointIndex ? dp(10) : dp(9);
            canvas.drawCircle(point.x, point.y, radius + dp(3), waypointOutlinePaint);
            Paint fillPaint = waypoint.type() == WaypointType.FORCED
                    ? forcedWaypointPaint
                    : waypointPaint;
            canvas.drawCircle(
                    point.x,
                    point.y,
                    radius,
                    i == draggingWaypointIndex ? waypointDragPaint : fillPaint
            );
            String number = String.valueOf(i + 1);
            waypointNumberPaint.setTextSize(number.length() >= 2 ? dp(9) : dp(11));
            Paint.FontMetrics metrics = waypointNumberPaint.getFontMetrics();
            float baseline = point.y - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(number, point.x, baseline, waypointNumberPaint);
        }
    }

    private void drawMarker(Canvas canvas, PointF point, int color) {
        markerOutlinePaint.setColor(color);
        canvas.drawCircle(point.x, point.y, dp(7), markerPaint);
        canvas.drawCircle(point.x, point.y, dp(7), markerOutlinePaint);
    }

    private PointF toScreenPoint(RoutePoint point) {
        return map.getProjection().toScreenLocation(
                new LatLng(point.latitude(), point.longitude())
        );
    }

    private void appendDraftPoint(PointF point) {
        if (draftPoints.isEmpty()) {
            draftPoints.add(point);
            return;
        }
        PointF previous = draftPoints.get(draftPoints.size() - 1);
        if (Math.hypot(point.x - previous.x, point.y - previous.y) >= minimumDraftSpacing) {
            draftPoints.add(point);
        }
    }

    private boolean handleWaypointTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedWaypointIndex = findWaypointAt(event.getX(), event.getY());
                if (pressedWaypointIndex < 0) {
                    return false;
                }
                draggingWaypointIndex = pressedWaypointIndex;
                draggingWaypoint = false;
                longPressHandled = false;
                downX = event.getX();
                downY = event.getY();
                handler.postDelayed(longPressRunnable, 550L);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressedWaypointIndex < 0) {
                    return false;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!draggingWaypoint && Math.hypot(dx, dy) > touchSlop) {
                    draggingWaypoint = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                if (draggingWaypoint) {
                    int targetIndex = findNearestWaypoint(event.getX(), event.getY());
                    if (targetIndex >= 0) {
                        draggingWaypointIndex = targetIndex;
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (pressedWaypointIndex >= 0 && draggingWaypoint && !longPressHandled) {
                    int targetIndex = findNearestWaypoint(event.getX(), event.getY());
                    if (targetIndex >= 0
                            && targetIndex != pressedWaypointIndex
                            && waypointEditListener != null) {
                        waypointEditListener.onWaypointMoved(pressedWaypointIndex, targetIndex);
                    }
                    performClick();
                }
                resetWaypointGesture();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetWaypointGesture();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int findWaypointAt(float x, float y) {
        float radius = dp(18);
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            PointF point = toScreenPoint(waypoints.get(i).point());
            double distance = Math.hypot(point.x - x, point.y - y);
            if (distance <= radius && distance < bestDistance) {
                bestIndex = i;
                bestDistance = distance;
            }
        }
        return bestIndex;
    }

    private int findNearestWaypoint(float x, float y) {
        if (waypoints.isEmpty()) {
            return -1;
        }
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            PointF point = toScreenPoint(waypoints.get(i).point());
            double distance = Math.hypot(point.x - x, point.y - y);
            if (distance < bestDistance) {
                bestIndex = i;
                bestDistance = distance;
            }
        }
        return bestIndex;
    }

    private void resetWaypointGesture() {
        handler.removeCallbacks(longPressRunnable);
        pressedWaypointIndex = -1;
        draggingWaypointIndex = -1;
        draggingWaypoint = false;
        longPressHandled = false;
    }

    private void commitDraft() {
        if (draftPoints.size() < 2) {
            draftPoints.clear();
            invalidate();
            return;
        }

        List<RoutePoint> previousRoute = new ArrayList<>(route);
        List<RoutePoint> rawStroke = new ArrayList<>(draftPoints.size());
        for (PointF point : draftPoints) {
            LatLng latLng = map.getProjection().fromScreenLocation(point);
            rawStroke.add(new RoutePoint(latLng.getLatitude(), latLng.getLongitude()));
        }
        List<RoutePoint> simplified = RouteSimplifier.simplify(rawStroke, 2.5);

        route.clear();
        route.addAll(RouteDraftMerger.merge(previousRoute, simplified, 1.0));
        draftPoints.clear();
        if (routeChangedListener != null) {
            routeChangedListener.onRouteChanged(previousRoute);
        }
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
