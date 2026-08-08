package com.bl0ck154.ninebotblocker;

import java.util.Locale;

/** Conservative model label detection. Protocol compatibility is still decided by a successful handshake. */
public final class ScooterIdentity {
    private ScooterIdentity() {}

    public static String displayModel(String advertisedName, String serial) {
        String sn = clean(serial).toUpperCase(Locale.US);
        String name = clean(advertisedName);
        String upper = name.toUpperCase(Locale.US);

        // Max/G30 family serial prefixes documented in the Ninebot community and observed on G30 variants.
        if (sn.startsWith("N4G") || sn.startsWith("N4YC") || sn.startsWith("NTG1")) {
            return "Ninebot Max G30";
        }

        String fromName = modelFromName(upper);
        if (fromName != null) return fromName;

        if (!name.isEmpty() && !upper.startsWith("NBSCOOTER")) return name;
        return "Ninebot / Segway Scooter";
    }

    private static String modelFromName(String upper) {
        if (upper.contains("MAX G2") || upper.contains(" G2")) return "Ninebot Max G2";
        if (upper.contains("G30")) return "Ninebot Max G30";
        if (upper.contains("F2 PRO")) return "Ninebot F2 Pro";
        if (upper.contains("F2 PLUS")) return "Ninebot F2 Plus";
        if (upper.contains("F2")) return "Ninebot F2";
        if (upper.contains("F40")) return "Ninebot F40";
        if (upper.contains("F30")) return "Ninebot F30";
        if (upper.contains("F25")) return "Ninebot F25";
        if (upper.contains("F20")) return "Ninebot F20";
        if (upper.contains("E45")) return "Ninebot E45";
        if (upper.contains("E25")) return "Ninebot E25";
        if (upper.contains("E22")) return "Ninebot E22";
        if (upper.contains("ES4")) return "Ninebot ES4";
        if (upper.contains("ES2")) return "Ninebot ES2";
        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\u0000", "").trim();
    }
}
