package com.geeroam.badwords;

public record FilterConfig(
        String region,
        String endpoint,
        int readTimeout,
        int connectTimeout,
        String service,
        String serviceImage,
        String ossEndpoint,
        String ossBucketName
) {
    public FilterConfig(String region, String endpoint, int readTimeout, int connectTimeout, String service) {
        this(region, endpoint, readTimeout, connectTimeout, service, null, null, null);
    }

    public FilterConfig(String region, String endpoint, int readTimeout, int connectTimeout,
                        String service, String serviceImage) {
        this(region, endpoint, readTimeout, connectTimeout, service, serviceImage, null, null);
    }

    public String serviceImage() {
        return serviceImage != null ? serviceImage : service;
    }

    public boolean hasOssConfig() {
        return ossEndpoint != null && !ossEndpoint.isBlank()
                && ossBucketName != null && !ossBucketName.isBlank();
    }
}
