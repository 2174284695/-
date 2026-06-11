package com.example.routesimulator.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.example.routesimulator.model.GeoMath;
import com.example.routesimulator.model.RoutePoint;
import com.example.routesimulator.model.RouteSimplifier;

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

    private final Paint routeOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint draftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PointF> draftPoints = new ArrayList<>();

    private MapLibreMap map;
    private List<RoutePoint> route = Collections.emptyList();
    private Mode mode = Mode.BROWSE;
    private RoutePoint activePoint;
    private OnRouteChangedListener routeChangedListener;
    private float minimumDraftSpacing;

    public RouteOverlayView(Context context) {
        this(context, null);
    }

    public RouteOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        minimumDraftSpacing = 8f * getResources().getDisplayMetrics().density;

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

        draftPaint.setColor(Color.rgb(28, 146, 97));
        draftPaint.setAlpha(210);
        draftPaint.setStrokeWidth(dp(5));
        draftPaint.setStyle(Paint.Style.STROKE);
        draftPaint.setStrokeCap(Paint.Cap.ROUND);
        draftPaint.setStrokeJoin(Paint.Join.ROUND);

        markerPaint.setColor(Color.WHITE);
        markerPaint.setStyle(Paint.Style.FILL);
        markerOutlinePaint.setColor(Color.rgb(19, 122, 80));
        markerOutlinePaint.setStrokeWidth(dp(3));
        markerOutlinePaint.setStyle(Paint.Style.STROKE);

        activePaint.setColor(Color.rgb(255, 138, 0));
        activePaint.setStyle(Paint.Style.FILL);
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

    public void setMode(Mode mode) {
        this.mode = mode;
        boolean drawsDirectly = mode == Mode.DRAW;
        setClickable(drawsDirectly);
        setFocusable(drawsDirectly);
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (map == null) {
            return;
        }
        drawRoute(canvas);
        drawDraft(canvas);
        drawActivePoint(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode != Mode.DRAW || map == null) {
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

    private void drawActivePoint(Canvas canvas) {
        if (activePoint == null) {
            return;
        }
        PointF point = toScreenPoint(activePoint);
        canvas.drawCircle(point.x, point.y, dp(10), markerPaint);
        canvas.drawCircle(point.x, point.y, dp(7), activePaint);
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

        if (!route.isEmpty() && !simplified.isEmpty()
                && GeoMath.distanceMeters(
                route.get(route.size() - 1),
                simplified.get(0)
        ) < 1.0) {
            simplified.remove(0);
        }
        route.addAll(simplified);
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
