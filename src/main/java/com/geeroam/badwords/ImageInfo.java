package com.geeroam.badwords;

public record ImageInfo(String url, String localPath, byte[] data) {
    public static ImageInfo ofUrl(String url) {
        return new ImageInfo(url, null, null);
    }

    public static ImageInfo ofLocal(String localPath) {
        return new ImageInfo(null, localPath, null);
    }

    public static ImageInfo ofBytes(byte[] data) {
        return new ImageInfo(null, null, data);
    }

    public boolean valid() {
        return (url != null && !url.isBlank())
                || (localPath != null && !localPath.isBlank())
                || (data != null && data.length > 0);
    }

    public boolean isLocal() {
        return url == null || url.isBlank();
    }
}
