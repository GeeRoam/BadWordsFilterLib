package com.geeroam.badwords;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.sdk.service.green20220302.AsyncClient;
import com.aliyun.sdk.service.green20220302.models.DescribeImageModerationResultRequest;
import com.aliyun.sdk.service.green20220302.models.DescribeImageModerationResultResponse;
import com.aliyun.sdk.service.green20220302.models.DescribeImageModerationResultResponseBody;
import com.aliyun.sdk.service.green20220302.models.DescribeUploadTokenRequest;
import com.aliyun.sdk.service.green20220302.models.DescribeUploadTokenResponse;
import com.aliyun.sdk.service.green20220302.models.DescribeUploadTokenResponseBody;
import com.aliyun.sdk.service.green20220302.models.ImageAsyncModerationRequest;
import com.aliyun.sdk.service.green20220302.models.ImageAsyncModerationResponse;
import com.aliyun.sdk.service.green20220302.models.TextModerationPlusRequest;
import com.aliyun.sdk.service.green20220302.models.TextModerationPlusResponse;
import com.aliyun.sdk.service.green20220302.models.TextModerationPlusResponseBody;
import com.google.gson.JsonObject;
import darabonba.core.client.ClientOverrideConfiguration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class GreenFilterPlugin implements FilterPlugin {
    private static final long POLL_INTERVAL_MS = 500;
    private static final long INITIAL_WAIT_MS = 200;

    private final Credentials credentials;
    private final FilterConfig config;
    private final File logDir;
    private Logger logger;
    private LoggerContext logCtx;
    private AsyncClient client;
    private OSS ossClient;
    private DescribeUploadTokenResponseBody.Data uploadToken;

    public GreenFilterPlugin(Credentials credentials, FilterConfig config, File logDir) {
        this.credentials = credentials;
        this.config = config;
        this.logDir = logDir;
    }

    @Override
    public String name() {
        return "green";
    }

    @Override
    public void init() {
        setupLogger();

        if (!credentials.valid()) {
            logger.warn("Green credentials not set, plugin disabled");
            return;
        }

        ClientOverrideConfiguration cfg = ClientOverrideConfiguration.create()
                .setEndpointOverride(config.endpoint())
                .setConnectTimeout(Duration.of(config.connectTimeout(), ChronoUnit.MILLIS))
                .setResponseTimeout(Duration.of(config.readTimeout(), ChronoUnit.MILLIS));

        this.client = AsyncClient.builder()
                .region(config.region())
                .overrideConfiguration(cfg)
                .credentialsProvider(StaticCredentialProvider.create(
                        Credential.builder()
                                .accessKeyId(credentials.accessKeyId())
                                .accessKeySecret(credentials.accessKeySecret())
                                .build()))
                .build();

        logger.info("GreenFilterPlugin initialized, textService={}, imageService={}",
                config.service(), config.serviceImage());

        if (config.hasOssConfig()) {
            this.ossClient = new OSSClientBuilder().build(
                    config.ossEndpoint(), credentials.accessKeyId(), credentials.accessKeySecret());
            logger.info("User OSS configured, bucket={}", config.ossBucketName());
        }
    }

    private void setupLogger() {
        logDir.mkdirs();

        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName("green-filter");

        String pattern = "[%d{yyyy-MM-dd HH:mm:ss}][%X{source}][%X{level}] %m%n";

        builder.add(builder.newAppender("green-file", "RollingFile")
                .addAttribute("fileName", new File(logDir, "current.log").getAbsolutePath())
                .addAttribute("filePattern", new File(logDir, "%d{yyyy-MM-dd}.log").getAbsolutePath())
                .add(builder.newLayout("PatternLayout").addAttribute("pattern", pattern))
                .addComponent(builder.newComponent("TimeBasedTriggeringPolicy")
                        .addAttribute("interval", "1")
                        .addAttribute("modulate", "true")));

        builder.add(builder.newRootLogger(Level.INFO)
                .add(builder.newAppenderRef("green-file")));

        BuiltConfiguration built = builder.build();
        logCtx = new LoggerContext("green-filter");
        logCtx.start(built);
        logger = logCtx.getLogger("green");
    }

    @Override
    public boolean isMatch(PlayerInfo playerInfo, String text) {
        if (client == null) {
            return false;
        }

        JsonObject object = new JsonObject();
        object.addProperty("content", text);

        TextModerationPlusRequest request = TextModerationPlusRequest.builder()
                .service(config.service())
                .serviceParameters(object.toString())
                .build();

        return doTextModerate(playerInfo, request, text);
    }

    @Override
    public boolean isMatchImage(PlayerInfo playerInfo, ImageInfo image) {
        if (client == null || !image.valid()) {
            return false;
        }

        JsonObject params = new JsonObject();
        params.addProperty("dataId", UUID.randomUUID().toString());

        String logSummary;

        if (!image.isLocal()) {
            params.addProperty("imageUrl", image.url());
            logSummary = image.url();
        } else {
            try {
                String localPath = resolveLocalPath(image);
                if (localPath == null) {
                    return false;
                }

                File file = new File(localPath);
                if (!file.exists()) {
                    return false;
                }

                byte[] raw = Files.readAllBytes(file.toPath());

                if (!uploadToOss(file, params)) {
                    String b64 = Base64.getEncoder().encodeToString(raw);
                    params.addProperty("imageUrl", "data:image;base64," + b64);
                    logger.info("Upload to OSS failed, fallback to base64");
                }

                logSummary = "base64:" + Base64.getEncoder().encodeToString(raw);
            } catch (IOException e) {
                logger.error("Failed to process local image", e);
                return false;
            }
        }

        ImageAsyncModerationRequest request = ImageAsyncModerationRequest.builder()
                .service(config.serviceImage())
                .serviceParameters(params.toString())
                .build();

        return doImageAsyncModerate(playerInfo, request, logSummary);
    }

    private String resolveLocalPath(ImageInfo image) throws IOException {
        if (image.localPath() != null && !image.localPath().isBlank()) {
            return image.localPath();
        }

        if (image.data() != null) {
            String ext = detectFormat(image.data());
            File tmp = File.createTempFile("green_", ext);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(image.data());
            }

            tmp.deleteOnExit();
            return tmp.getAbsolutePath();
        }

        return null;
    }

    private static String detectFormat(byte[] data) {
        if (data.length < 4) return ".png";
        int a = data[0] & 0xFF, b = data[1] & 0xFF, c = data[2] & 0xFF, d = data[3] & 0xFF;
        if (a == 0xFF && b == 0xD8) return ".jpg";
        if (a == 0x89 && b == 0x50 && c == 0x4E && d == 0x47) return ".png";
        if (a == 0x47 && b == 0x49 && c == 0x46) return ".gif";
        if (a == 0x52 && b == 0x49 && c == 0x46 && d == 0x46) return ".webp";
        if (a == 0x42 && b == 0x4D) return ".bmp";
        return ".png";
    }

    private boolean uploadToOss(File file, JsonObject params) {
        if (!file.exists()) {
            logger.error("File not found: {}", file.getAbsolutePath());
            return false;
        }

        if (config.hasOssConfig()) {
            return uploadToUserOss(file, params);
        }
        return uploadToGreenOss(file, params);
    }

    private boolean uploadToUserOss(File file, JsonObject params) {
        String key = "moderation/" + UUID.randomUUID() + suffix(file.getName());
        PutObjectRequest putReq = new PutObjectRequest(config.ossBucketName(), key, file);
        ossClient.putObject(putReq);
        String url = "https://" + config.ossBucketName() + "." + config.ossEndpoint() + "/" + key;
        params.addProperty("imageUrl", url);
        return true;
    }

    private boolean uploadToGreenOss(File file, JsonObject params) {
        try {
            boolean cached = uploadToken != null;
            DescribeUploadTokenResponseBody.Data token = getUploadToken();
            if (token == null) {
                return false;
            }

            if (!cached || ossClient == null) {
                if (ossClient != null) {
                    ossClient.shutdown();
                }
                ossClient = new OSSClientBuilder().build(
                        token.getOssInternetEndPoint(),
                        token.getAccessKeyId(),
                        token.getAccessKeySecret(),
                        token.getSecurityToken());
            }

            String objectName = token.getFileNamePrefix() + UUID.randomUUID() + suffix(file.getName());
            PutObjectRequest putReq = new PutObjectRequest(token.getBucketName(), objectName, file);
            ossClient.putObject(putReq);

            params.addProperty("ossBucketName", token.getBucketName());
            params.addProperty("ossObjectName", objectName);
            return true;
        } catch (Exception e) {
            logger.error("Upload to Green OSS failed", e);
            return false;
        }
    }

    private String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private DescribeUploadTokenResponseBody.Data getUploadToken() {
        long now = System.currentTimeMillis() / 1000;
        if (uploadToken != null && uploadToken.getExpiration() != null
                && uploadToken.getExpiration() > now) {
            return uploadToken;
        }

        try {
            DescribeUploadTokenRequest request = DescribeUploadTokenRequest
                    .builder()
                    .build();

            CompletableFuture<DescribeUploadTokenResponse> response = client.describeUploadToken(request);
            DescribeUploadTokenResponse r = response.get(config.readTimeout(), TimeUnit.MILLISECONDS);

            if (r.getStatusCode() == 200 && r.getBody().getCode() == 200) {
                uploadToken = r.getBody().getData();
                return uploadToken;
            }
        } catch (Exception e) {
            logger.error("Failed to get upload token", e);
        }
        return null;
    }

    private boolean doTextModerate(PlayerInfo playerInfo, TextModerationPlusRequest request, String content) {
        try {
            CompletableFuture<TextModerationPlusResponse> response = client.textModerationPlus(request);
            TextModerationPlusResponse c = response.get(config.readTimeout(), TimeUnit.MILLISECONDS);

            if (c.getStatusCode() != 200) {
                logger.error("text response not success. status: {}", c.getStatusCode());
                return false;
            }

            TextModerationPlusResponseBody result = c.getBody();
            if (result.getCode() != 200) {
                logger.error("text moderation not success. code: {}", result.getCode());
                return false;
            }

            TextModerationPlusResponseBody.Data data = result.getData();
            RiskLevel level = RiskLevel.valueOf(data.getRiskLevel().toUpperCase());

            ThreadContext.put("source", playerInfo.source());
            ThreadContext.put("level", level.toString());
            logger.info("[TEXT] {}: {}", playerInfo.playerId(), content);
            ThreadContext.clearAll();

            return level == RiskLevel.HIGH;
        } catch (Exception e) {
            logger.error("text moderation failed", e);
            return false;
        }
    }

    private boolean doImageAsyncModerate(PlayerInfo playerInfo, ImageAsyncModerationRequest request, String logSummary) {
        try {
            CompletableFuture<ImageAsyncModerationResponse> submit = client.imageAsyncModeration(request);
            ImageAsyncModerationResponse rsp = submit.get(config.readTimeout(), TimeUnit.MILLISECONDS);

            int submitStatus = rsp.getStatusCode();
            int submitCode = rsp.getBody().getCode();
            if (submitStatus == 404 || submitCode == 404) {
                return false;
            }
            if (submitStatus != 200 || submitCode != 200) {
                logger.error("image async submit failed. status={} code={}", submitStatus, submitCode);
                return false;
            }

            String reqId = rsp.getBody().getData().getReqId();
            if (reqId == null || reqId.isBlank()) {
                return false;
            }

            Thread.sleep(INITIAL_WAIT_MS);

            long deadline = System.currentTimeMillis() + config.readTimeout();
            while (System.currentTimeMillis() < deadline) {
                DescribeImageModerationResultRequest pollReq =
                        DescribeImageModerationResultRequest.builder().reqId(reqId).build();
                CompletableFuture<DescribeImageModerationResultResponse> poll =
                        client.describeImageModerationResult(pollReq);
                DescribeImageModerationResultResponse pollRsp = poll.get(config.readTimeout(), TimeUnit.MILLISECONDS);

                int pollStatus = pollRsp.getStatusCode();
                int pollCode = pollRsp.getBody().getCode();
                if (pollStatus == 404 || pollCode == 280) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }
                if (pollStatus == 401 || pollCode == 401) {
                    logger.error("image poll auth failed. status={} code={}", pollStatus, pollCode);
                    return false;
                }
                if (pollStatus != 200 || pollCode != 200) {
                    logger.error("image poll failed. status={} code={}", pollStatus, pollCode);
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                DescribeImageModerationResultResponseBody.Data data = pollRsp.getBody().getData();
                if (data == null || data.getResult() == null || data.getResult().isEmpty()) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                String riskLevel = data.getRiskLevel();

                List<DescribeImageModerationResultResponseBody.Result> results = data.getResult();
                StringBuilder details = new StringBuilder();
                for (DescribeImageModerationResultResponseBody.Result r : results) {
                    if (!details.isEmpty()) {
                        details.append(", ");
                    }
                    details.append(r.getLabel()).append(":").append(r.getConfidence());
                }

                ThreadContext.put("source", playerInfo.source());
                ThreadContext.put("level", riskLevel != null ? riskLevel.toUpperCase() : "NONE");
                logger.info("[IMAGE] {}: {} risk={} labels=[{}]",
                        playerInfo.playerId(), logSummary, riskLevel, details);
                ThreadContext.clearAll();

                return "high".equalsIgnoreCase(riskLevel);
            }

            logger.warn("image async moderation timed out after {}ms", config.readTimeout());
        } catch (Exception e) {
            logger.error("image async moderation failed", e);
        }
        return false;
    }

    @Override
    public void shutdown() {
        if (client != null) {
            client.close();
        }
        if (ossClient != null) {
            ossClient.shutdown();
        }
        if (logCtx != null) {
            logCtx.close();
        }
    }
}
