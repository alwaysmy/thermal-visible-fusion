package org.thermalfusion.storage;

/** Android-version rules without an Android runtime dependency. */
public final class StoragePolicy {
    public static final String ALBUM = "ThermalFusion";
    public static final String RELATIVE_PATH = "Pictures/" + ALBUM + "/";
    private StoragePolicy() {}

    public static boolean usesMediaStore(int api) {
        requireSupported(api);
        return api >= 29;
    }

    public static boolean needsLegacyWritePermission(int api) {
        requireSupported(api);
        return api <= 28;
    }

    public static boolean needsRuntimeWritePermission(int api) {
        requireSupported(api);
        return api >= 23 && api <= 28;
    }

    private static void requireSupported(int api) {
        if (api < 21) throw new IllegalArgumentException("Minimum Android API is 21");
    }
}
