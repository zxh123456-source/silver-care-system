package com.shanzhu.beadhouse.common.config.security.handler;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码组件。
 *
 * 新密码使用 BCrypt；历史 AES-CBC 密文仍可登录，成功登录后由账号服务
 * 重新保存为 BCrypt，支持无感渐进迁移。
 */
@Component
public class PasswordEncoderImpl implements PasswordEncoder {
    private static final String BCRYPT_PREFIX = "$2";
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        if (encodedPassword.startsWith(BCRYPT_PREFIX)) {
            return bcrypt.matches(rawPassword, encodedPassword);
        }
        return com.shanzhu.beadhouse.common.util.AesUtil.aesMatch(rawPassword.toString(), encodedPassword);
    }

    public boolean isLegacyEncoded(String encodedPassword) {
        return encodedPassword != null && !encodedPassword.startsWith(BCRYPT_PREFIX);
    }
}
