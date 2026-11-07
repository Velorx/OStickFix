package com.wawek.stickfix;

/**
 * Root launcher commands, sent to PServerBinder. They find the daemon through
 * `pm path`, so they keep working after the app is updated.
 */
final class Scripts {
    // Shared by both scripts: stop a running daemon (it puts the real pad back on exit).
    private static final String STOP_BODY = ""
            + "RUN=/data/local/tmp/stickfix\n"
            + "if [ -f $RUN/pid ]; then\n"
            + "  OLD=$(cat $RUN/pid)\n"
            + "  if grep -q stickfixd /proc/$OLD/cmdline 2>/dev/null; then kill $OLD; sleep 1; fi\n"
            + "  rm -f $RUN/pid\n"
            + "fi\n";

    // %1$s = daemon path, %2$s = config path. pservice can't use `pm`, so the app fills these in.
    private static final String START = "#!/system/bin/sh\n"
            + "# StickFix launcher (run as root through PServerBinder).\n"
            + "BIN='%1$s'\n"
            + "CONF='%2$s'\n"
            + "mkdir -p /data/local/tmp/stickfix\n"
            + STOP_BODY
            + "setsid \"$BIN\" --config \"$CONF\" </dev/null >>$RUN/log 2>&1 &\n"
            + "echo $! > $RUN/pid\n";

    private static final String STOP = "#!/system/bin/sh\n"
            + "# StickFix: stop the remapper and give the original controller back to Android.\n"
            + STOP_BODY
            + "echo \"StickFix stopped\"\n";

    /** The launcher, run as root through PServerBinder at boot or from the app. */
    static String startCommand(android.content.Context ctx) throws java.io.IOException {
        String bin = ctx.getApplicationInfo().nativeLibraryDir + "/libstickfixd.so";
        String conf = rootPath(ctx, "stickfix.conf");
        return "sh " + write(ctx, "stickfix-start.sh", String.format(java.util.Locale.ROOT, START, bin, conf));
    }

    static String stopCommand(android.content.Context ctx) throws java.io.IOException {
        return "sh " + write(ctx, "stickfix-stop.sh", STOP);
    }

    // PServer only runs single-line commands, so the scripts live in the app's external
    // files dir, which root sees through its backing path on /data/media.
    private static String rootPath(android.content.Context ctx, String name) {
        return "/data/media/0/Android/data/" + ctx.getPackageName() + "/files/" + name;
    }

    private static String write(android.content.Context ctx, String name, String body) throws java.io.IOException {
        java.io.File dir = ctx.getExternalFilesDir(null);
        if (dir == null) throw new java.io.IOException("external files dir unavailable");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            o.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return rootPath(ctx, name);
    }
}
