package com.shanzhu.beadhouse.common.config.security.config;

import com.shanzhu.beadhouse.common.constant.Constant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Component
public class SecretConfigurationGuard {
    @Value("${spring.datasource.druid.password:}") private String databasePassword;
    @Value("${spring.redis.password:}") private String redisPassword;
    @Value("${ai.rag.enabled:true}") private boolean ragEnabled;
    @Value("${ai.rag.internal-token:}") private String ragToken;
    @Value("${security.password-reset.email-enabled:false}") private boolean resetEmailEnabled;
    @Value("${ai.provider.enabled:false}") private boolean providerEnabled;
    @Value("${ai.provider.api-key:}") private String providerKey;

    @PostConstruct
    public void validate() {
        requireSecret("DB_PASSWORD", databasePassword, 1);
        requireSecret("REDIS_PASSWORD", redisPassword, 1);
        requireSecret("JWT_SECRET", Constant.TOKEN_SECRET, 32);
        if (ragEnabled) requireSecret("AI_RAG_INTERNAL_TOKEN", ragToken, 32);
        if (resetEmailEnabled) {
            requireSecret("MAIL_HOST", Constant.MAIL_HOST, 1);
            requireSecret("MAIL_ADDRESS", Constant.MAIL, 1);
            requireSecret("MAIL_PASSWORD", Constant.PASS, 1);
        }
        if (providerEnabled) requireSecret("AI_PROVIDER_API_KEY", providerKey, 1);
    }

    public static void requireSecret(String name, String value, int minimumBytes) {
        if (value == null || value.trim().isEmpty() || value.getBytes(StandardCharsets.UTF_8).length < minimumBytes
                || value.startsWith("replace-") || value.startsWith("change-") || value.startsWith("请填写")
                || "123456".equals(value) || "local-dev-token".equals(value) || "minioadmin".equals(value)
                || "redis-dev-password".equals(value) || "local-dev-jwt-secret-change-before-use-2026".equals(value))
            throw new IllegalStateException("请配置有效的 " + name + "（不输出密钥值）");
    }
}
