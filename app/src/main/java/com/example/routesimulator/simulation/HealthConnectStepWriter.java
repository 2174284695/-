package com.example.routesimulator.simulation;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.OutcomeReceiver;

import androidx.annotation.RequiresApi;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

@RequiresApi(34)
public final class HealthConnectStepWriter {
    private static final Duration WRITE_INTERVAL = Duration.ofSeconds(15);

    private final HealthConnectManager manager;
    private final Executor callbackExecutor;
    private final Device sourceDevice;

    private Instant intervalStart = Instant.now();
    private long pendingSteps;
    private boolean writeInFlight;

    public HealthConnectStepWriter(Context context) {
        manager = context.getSystemService(HealthConnectManager.class);
        callbackExecutor = context.getMainExecutor();
        sourceDevice = new Device.Builder()
                .setManufacturer(Build.MANUFACTURER)
                .setModel(Build.MODEL)
                .setType(Device.DEVICE_TYPE_PHONE)
                .build();
    }

    public static boolean canWrite(Context context) {
        return context.checkSelfPermission(HealthPermissions.WRITE_STEPS)
                == PackageManager.PERMISSION_GRANTED
                && context.getSystemService(HealthConnectManager.class) != null;
    }

    public void addSteps(long addedSteps, Instant now) {
        if (addedSteps > 0L) {
            pendingSteps += addedSteps;
        }
        flushIfNeeded(now, false);
    }

    public void close() {
        flushIfNeeded(Instant.now(), true);
    }

    private void flushIfNeeded(Instant now, boolean force) {
        if (manager == null || writeInFlight || pendingSteps <= 0L) {
            return;
        }
        if (!force && Duration.between(intervalStart, now).compareTo(WRITE_INTERVAL) < 0) {
            return;
        }

        Instant start = intervalStart;
        Instant end = now.isAfter(start) ? now : start.plusMillis(1);
        long stepsToWrite = pendingSteps;
        pendingSteps = 0L;
        intervalStart = end;
        writeInFlight = true;

        ZoneOffset startOffset = ZoneId.systemDefault().getRules().getOffset(start);
        ZoneOffset endOffset = ZoneId.systemDefault().getRules().getOffset(end);
        Metadata metadata = new Metadata.Builder()
                .setClientRecordId("route-simulator-" + start.toEpochMilli())
                .setClientRecordVersion(1L)
                .setDevice(sourceDevice)
                .setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY)
                .build();
        StepsRecord record = new StepsRecord.Builder(metadata, start, end, stepsToWrite)
                .setStartZoneOffset(startOffset)
                .setEndZoneOffset(endOffset)
                .build();
        List<Record> records = Collections.singletonList(record);

        manager.insertRecords(
                records,
                callbackExecutor,
                new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                    @Override
                    public void onResult(InsertRecordsResponse result) {
                        writeInFlight = false;
                    }

                    @Override
                    public void onError(HealthConnectException error) {
                        pendingSteps += stepsToWrite;
                        if (start.isBefore(intervalStart)) {
                            intervalStart = start;
                        }
                        writeInFlight = false;
                    }
                }
        );
    }
}
