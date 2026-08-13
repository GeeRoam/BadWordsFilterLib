package com.geeroam.badwords;

public interface FilterPlugin {
    String name();

    void init();

    boolean isMatch(PlayerInfo playerInfo, String text);

    default boolean isMatchImage(PlayerInfo playerInfo, ImageInfo image) {
        return false;
    }

    void shutdown();
}
