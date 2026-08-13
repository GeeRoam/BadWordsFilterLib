package com.geeroam.badwords;

public record Credentials(String accessKeyId, String accessKeySecret) {
    public boolean valid() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }
}
