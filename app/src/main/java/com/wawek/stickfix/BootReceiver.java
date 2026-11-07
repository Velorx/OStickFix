package com.wawek.stickfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service after boot; the service launches stickfixd through PServerBinder. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Engine.get(ctx).enabled()) {
            StickFixService.start(ctx);
        }
    }
}
