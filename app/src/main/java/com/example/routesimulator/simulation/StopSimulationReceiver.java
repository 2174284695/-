package com.example.routesimulator.simulation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StopSimulationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent stopIntent = new Intent(context, SimulationService.class)
                .setAction(SimulationService.ACTION_STOP);
        context.startService(stopIntent);
    }
}
