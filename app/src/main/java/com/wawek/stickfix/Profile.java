package com.wawek.stickfix;

import org.json.JSONException;
import org.json.JSONObject;

/** A pair of stick configs, either the default or bound to one game's package. */
final class Profile {
    /** Empty for the default profile. */
    final String pkg;
    String label;
    StickConfig left = new StickConfig();
    StickConfig right = new StickConfig();

    Profile(String pkg, String label) {
        this.pkg = pkg;
        this.label = label;
    }

    boolean isDefault() {
        return pkg.isEmpty();
    }

    Profile copyFor(String newPkg, String newLabel) {
        Profile p = new Profile(newPkg, newLabel);
        p.left = left.copy();
        p.right = right.copy();
        return p;
    }

    String toSetCommand() {
        return "SET " + left.toSet("l") + " " + right.toSet("r");
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("pkg", pkg).put("label", label)
                .put("l", left.toJson()).put("r", right.toJson());
    }

    static Profile fromJson(JSONObject o) {
        Profile p = new Profile(o.optString("pkg", ""), o.optString("label", "Default"));
        p.left = StickConfig.fromJson(o.optJSONObject("l"));
        p.right = StickConfig.fromJson(o.optJSONObject("r"));
        return p;
    }
}
