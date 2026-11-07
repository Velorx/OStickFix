package com.wawek.stickfix;

import android.os.IBinder;
import android.os.Parcel;

import java.nio.charset.StandardCharsets;

/**
 * AYN's stock PServerBinder service runs shell commands as root. Same call as
 * Odin Hub / P.U.L.S.E. / ClusterTune: transact(0) with a String[]{cmd, captureFlag},
 * reply is the command output as a byte array.
 *
 * StickFix only ever sends the fixed launcher commands from {@link Scripts}.
 */
final class PServer {
    private PServer() {
    }

    static IBinder binder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getDeclaredMethod("getService", String.class).invoke(null, "PServerBinder");
        } catch (Exception e) {
            return null;
        }
    }

    static boolean available() {
        return binder() != null;
    }

    /** Runs cmd as root and returns its output (may be empty on firmwares without capture). Blocking. */
    static String run(String cmd) throws Exception {
        IBinder b = binder();
        if (b == null) throw new IllegalStateException("PServerBinder not available on this firmware");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeStringArray(new String[]{cmd, "1"});
            if (!b.transact(0, data, reply, 0)) throw new IllegalStateException("PServerBinder rejected the command");
            byte[] out = null;
            try {
                out = reply.createByteArray();
            } catch (RuntimeException ignored) {
            }
            return out == null ? "" : new String(out, StandardCharsets.UTF_8).trim();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
