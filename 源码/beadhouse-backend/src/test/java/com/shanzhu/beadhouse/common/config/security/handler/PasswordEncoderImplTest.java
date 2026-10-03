package com.shanzhu.beadhouse.common.config.security.handler;

import com.shanzhu.beadhouse.common.util.AesUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderImplTest {
    private final PasswordEncoderImpl encoder = new PasswordEncoderImpl();

    @Test
    void newPasswordsUseBcrypt() {
        String encoded = encoder.encode("change-me");

        assertThat(encoded).startsWith("$2");
        assertThat(encoder.matches("change-me", encoded)).isTrue();
        assertThat(encoder.matches("wrong", encoded)).isFalse();
        assertThat(encoder.isLegacyEncoded(encoded)).isFalse();
    }

    @Test
    void legacyAesPasswordsRemainReadableAndAreMarkedForUpgrade() {
        String legacy = AesUtil.aesEncode("legacy-pass");

        assertThat(encoder.matches("legacy-pass", legacy)).isTrue();
        assertThat(encoder.matches("wrong", legacy)).isFalse();
        assertThat(encoder.isLegacyEncoded(legacy)).isTrue();
    }
}
