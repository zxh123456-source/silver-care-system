package com.shanzhu.beadhouse.common.config.security.handler;

import com.shanzhu.beadhouse.common.config.security.config.SecretConfigurationGuard;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecretConfigurationGuardTest {
    @Test void rejectsMissingWeakAndTemplateSecretsWithoutPrintingThem() {
        for (String value : new String[]{"", "123456", "local-dev-token", "replace-this-long-placeholder-secret-12345"}) {
            assertThatThrownBy(() -> SecretConfigurationGuard.requireSecret("JWT_SECRET", value, 32))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        }
        assertThatThrownBy(() -> SecretConfigurationGuard.requireSecret("JWT_SECRET", "sensitive-marker", 32))
                .hasMessageNotContaining("sensitive-marker");
    }
    @Test void acceptsExplicitlySuppliedSecrets() {
        assertThatCode(() -> SecretConfigurationGuard.requireSecret("JWT_SECRET", "test-only-secret-that-has-at-least-32-bytes", 32)).doesNotThrowAnyException();
    }
}
