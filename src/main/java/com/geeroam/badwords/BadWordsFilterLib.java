package com.geeroam.badwords;

public final class BadWordsFilterLib {
    private static FilterPlugin plugin;

    public static void init(FilterPlugin p) {
        plugin = p;
        plugin.init();
    }

    public static boolean isMatch(PlayerInfo playerInfo, String text) {
        return plugin != null && plugin.isMatch(playerInfo, text);
    }

    public static boolean isMatchImage(PlayerInfo playerInfo, ImageInfo image) {
        return plugin != null && plugin.isMatchImage(playerInfo, image);
    }

    public static void shutdown() {
        if (plugin != null) {
            plugin.shutdown();
        }
        plugin = null;
    }
}
