package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.Constant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/** Redis scripts make throttling, attempt limits and consumption atomic across servers. */
@Component
public class PasswordResetGuard {
    @Resource private StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>(
            "if redis.call('EXISTS',KEYS[2])==1 then return 0 end; " +
            "redis.call('SET',KEYS[2],'1','EX',60); redis.call('SET',KEYS[1],ARGV[1],'EX',300); " +
            "redis.call('DEL',KEYS[3]); return 1", Long.class);
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if not v then return 0 end; " +
            "local n=redis.call('INCR',KEYS[2]); if n==1 then redis.call('EXPIRE',KEYS[2],300) end; " +
            "if n>5 then redis.call('DEL',KEYS[1]); return 0 end; " +
            "if v==ARGV[1] then redis.call('DEL',KEYS[1],KEYS[2]); return 1 end; " +
            "if n==5 then redis.call('DEL',KEYS[1]) end; return 0", Long.class);

    public void throttle(String action, String account, String ip) {
        limit(action + ":ip:" + digest(ip), 30);
        limit(action + ":account:" + digest(account), 5);
    }
    private void limit(String key, int maximum) {
        Long count = redis.execute(RATE, Collections.singletonList("password-reset:rate:" + key), "900");
        if (count == null || count > maximum) throw new BusinessRuntimeException(429, "请求过于频繁，请稍后重试");
    }
    public void issue(String account, String code) {
        String key = key(account);
        Long result = redis.execute(ISSUE, Arrays.asList(key, key + ":cooldown", key + ":attempts"), digest(account + "\n" + code));
        if (!Long.valueOf(1).equals(result)) throw new BusinessRuntimeException(429, "请等待60秒后重新发送");
    }
    public void consume(String account, String code) {
        String key = key(account);
        Long result = redis.execute(VERIFY, Arrays.asList(key, key + ":attempts"), digest(account + "\n" + code));
        if (!Long.valueOf(1).equals(result)) throw new BusinessRuntimeException(400, "验证码无效或已过期，请重新申请");
    }
    public void discard(String account) { redis.delete(key(account)); }
    private String key(String account) { return "password-reset:code:" + digest(account); }
    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Constant.TOKEN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(value.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException("Password reset digest unavailable", e); }
    }
}
