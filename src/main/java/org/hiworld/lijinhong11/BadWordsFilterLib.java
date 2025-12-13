package org.hiworld.lijinhong11;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.green20220302.models.TextModerationPlusResponse;
import com.aliyun.green20220302.models.TextModerationPlusResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.SystemUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

public class BadWordsFilterLib {
    private static Logger LOGGER;

    private static Properties CONFIG;
    private static Config GREEN_CONFIG;

    public static void init() {
        File configFile = new File(SystemUtils.getUserHome(), ".badwords/config.properties");
        File parent = configFile.getParentFile();
        if (!parent.exists() || !configFile.exists()) {
            parent.mkdirs();
            try (InputStream is = Objects.requireNonNull(BadWordsFilterLib.class.getClassLoader().getResource("config.properties")).openStream()) {
                if (is == null) {
                    throw new NullPointerException("resource is null");
                }
                Files.copy(is, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            CONFIG = new Properties();
            CONFIG.load(new FileReader(configFile));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        LoggerContext ctx = new LoggerContext("BanWordsFilterContext");
        try {
            ctx.setConfigLocation(Objects.requireNonNull(BadWordsFilterLib.class.getClassLoader().getResource("log4j2.xml")).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        ctx.start();

        LOGGER = ctx.getLogger("chat");
    }

    public static boolean isMatch(PlayerInfo playerInfo, String text) {
        if (GREEN_CONFIG == null) {
            Config config = new Config();

            String accessId = SystemUtils.getEnvironmentVariable("ALIYUN_ACCESS_ID", "");
            if (accessId.isBlank()) {
                return false;
            }

            String accessSecret = SystemUtils.getEnvironmentVariable("ALIYUN_ACCESS_SECRET", "");
            if (accessSecret.isBlank()) {
                return false;
            }

            config.setAccessKeyId(accessId);
            config.setAccessKeySecret(accessSecret);

            config.setRegionId(CONFIG.getProperty("region"));
            config.setEndpoint(CONFIG.getProperty("endpoint"));

            config.setReadTimeout(Integer.valueOf(CONFIG.getProperty("readTimeout")));
            config.setConnectTimeout(Integer.valueOf(CONFIG.getProperty("connectTimeout")));

            GREEN_CONFIG = config;
        }

        JsonObject object = new JsonObject();
        object.addProperty("content", text);

        TextModerationPlusRequest request = new TextModerationPlusRequest();
        request.setService(CONFIG.getProperty("service"));
        request.setServiceParameters(object.toString());

        try {
            Client client = new Client(GREEN_CONFIG);
            TextModerationPlusResponse response = client.textModerationPlus(request);
            if (response.getStatusCode() == 200) {
                TextModerationPlusResponseBody result = response.getBody();
                Integer code = result.getCode();
                if (200 == code) {
                    TextModerationPlusResponseBody.TextModerationPlusResponseBodyData data = result.getData();
                    RiskLevel level = RiskLevel.valueOf(data.getRiskLevel().toUpperCase());
                    switch (level) {
                        case HIGH -> {
                            doLog(playerInfo, level, text);
                            return true;
                        }
                        case MEDIUM, LOW -> {
                            doLog(playerInfo, level, text);
                            return false;
                        }
                        case NONE -> {
                            return false;
                        }
                    }
                } else {
                    System.out.println("text moderation not success. code:" + code);
                }
            } else {
                System.out.println("response not success. status:" + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    public static void shutdown() {

    }

    private static void doLog(PlayerInfo info, RiskLevel level, String text) {
        ThreadContext.put("source", info.source());
        ThreadContext.put("level", level.toString());

        LOGGER.info("{}: {}", info.playerId(), text);

        ThreadContext.clearAll();
    }
}
