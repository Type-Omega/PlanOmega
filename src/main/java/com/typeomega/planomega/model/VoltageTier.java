package com.typeomega.planomega.model;

/**
 * GregTech CEu Modern voltage tiers (ULV through MAX) for display badges and overclock calculations.
 */
public enum VoltageTier {
    ULV(0, "ULV", 8L, 0xFF94A3B8),
    LV(1, "LV", 32L, 0xFFD1D5DB),
    MV(2, "MV", 128L, 0xFFFB923C),
    HV(3, "HV", 512L, 0xFFFACC15),
    EV(4, "EV", 2048L, 0xFFA78BFA),
    IV(5, "IV", 8192L, 0xFF60A5FA),
    LuV(6, "LuV", 32768L, 0xFFF472B6),
    ZPM(7, "ZPM", 131072L, 0xFFF87171),
    UV(8, "UV", 524288L, 0xFF2DD4BF),
    UHV(9, "UHV", 2097152L, 0xFFDC2626),
    UEV(10, "UEV", 8388608L, 0xFF4ADE80),
    UIV(11, "UIV", 33554432L, 0xFF14B8A6),
    UXV(12, "UXV", 134217728L, 0xFFEAB308),
    OpV(13, "OpV", 536870912L, 0xFF3B82F6),
    MAX(14, "MAX", 2147483647L, 0xFFEF4444);

    private final int index;
    private final String shortName;
    private final long voltage;
    private final int badgeColor;

    VoltageTier(int index, String shortName, long voltage, int badgeColor) {
        this.index = index;
        this.shortName = shortName;
        this.voltage = voltage;
        this.badgeColor = badgeColor;
    }

    public int index() {
        return index;
    }

    public String shortName() {
        return shortName;
    }

    public long voltage() {
        return voltage;
    }

    public int badgeColor() {
        return badgeColor;
    }

    public static VoltageTier byIndex(int index) {
        VoltageTier[] values = values();
        if (index < 0) {
            return ULV;
        }
        if (index >= values.length) {
            return MAX;
        }
        return values[index];
    }

    public static VoltageTier fromEut(long eut) {
        long abs = Math.abs(eut);
        for (VoltageTier tier : values()) {
            if (abs <= tier.voltage) {
                return tier;
            }
        }
        return MAX;
    }
}
