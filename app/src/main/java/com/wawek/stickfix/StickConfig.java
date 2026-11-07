package com.wawek.stickfix;

import org.json.JSONException;
import org.json.JSONObject;

/** Shaping parameters for one stick. Must stay in sync with shape() in stickfixd.c. */
final class StickConfig {
    double inner = 0;   // radial deadzone, fraction of full deflection
    double outer = 1;   // deflection treated as 100%
    double anti = 0;    // output starts here once outside the deadzone
    double curve = 1;   // response exponent

    StickConfig copy() {
        StickConfig c = new StickConfig();
        c.inner = inner;
        c.outer = outer;
        c.anti = anti;
        c.curve = curve;
        return c;
    }

    /** Output magnitude (0..1) for a raw magnitude (0..~1.4). */
    double shape(double r) {
        if (r <= inner || r < 1e-6) return 0;
        double t = Math.min(1, (r - inner) / Math.max(0.05, outer - inner));
        t = Math.pow(t, curve);
        return anti + (1 - anti) * t;
    }

    String toSet(String prefix) {
        return String.format(java.util.Locale.ROOT, "%1$s.inner=%2$.4f %1$s.outer=%3$.4f %1$s.anti=%4$.4f %1$s.curve=%5$.3f",
                prefix, inner, outer, anti, curve);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("inner", inner).put("outer", outer).put("anti", anti).put("curve", curve);
    }

    static StickConfig fromJson(JSONObject o) {
        StickConfig c = new StickConfig();
        if (o == null) return c;
        c.inner = o.optDouble("inner", 0);
        c.outer = o.optDouble("outer", 1);
        c.anti = o.optDouble("anti", 0);
        c.curve = o.optDouble("curve", 1);
        return c;
    }
}
