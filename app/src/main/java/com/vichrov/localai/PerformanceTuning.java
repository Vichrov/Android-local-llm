package com.vichrov.localai;

import android.content.Context;
import android.os.PowerManager;

/** Conservative Snapdragon 855+ tuning: use more CPU only while thermals allow it. */
final class PerformanceTuning {
    private PerformanceTuning() { }

    static int threads(Context context) {
        int cores=Math.max(1,Runtime.getRuntime().availableProcessors());
        int wanted=cores>=8?6:cores>=6?4:Math.min(4,cores);
        PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
        if(pm!=null) {
            int thermal=pm.getCurrentThermalStatus();
            if(thermal>=PowerManager.THERMAL_STATUS_SEVERE) wanted=Math.min(wanted,2);
            else if(thermal>=PowerManager.THERMAL_STATUS_MODERATE) wanted=Math.min(wanted,4);
        }
        return Math.max(1,Math.min(wanted,cores));
    }

    static String summary(Context context) {
        return "CPU · KleidiAI auto · "+threads(context)+" потоков · контекст 2048";
    }
}
