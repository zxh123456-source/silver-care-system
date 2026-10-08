package com.shanzhu.beadhouse.service.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in test against a dedicated ephemeral Redis; never uses the application database. */
@EnabledIfEnvironmentVariable(named="RESET_TEST_REDIS_PORT", matches="[0-9]+")
class PasswordResetGuardIntegrationTest {
    @Test void redisEnforcesCooldownAttemptsReplayAndConcurrentConsumption() throws Exception {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("RESET_TEST_REDIS_PORT")));
        JedisConnectionFactory factory = new JedisConnectionFactory(config); factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        PasswordResetGuard guard = new PasswordResetGuard(); ReflectionTestUtils.setField(guard,"redis",redis);
        String prefix = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            String a=prefix+"@example.com"; guard.issue(a,"012345");
            assertThatThrownBy(() -> guard.issue(a,"999999")).hasMessageContaining("60秒");
            List<Callable<Boolean>> requests = new ArrayList<>();
            for(int i=0;i<8;i++) requests.add(() -> {try {guard.consume(a,"012345");return true;}catch(RuntimeException e){return false;}});
            long successes=0; for(Future<Boolean> f:pool.invokeAll(requests)) if(f.get()) successes++;
            assertThat(successes).isEqualTo(1);
            assertThatThrownBy(() -> guard.consume(a,"012345")).hasMessageContaining("无效");
            String b=prefix+"-attempts@example.com"; guard.issue(b,"654321");
            for(int i=0;i<5;i++) assertThatThrownBy(() -> guard.consume(b,"000000")).hasMessageContaining("无效");
            assertThatThrownBy(() -> guard.consume(b,"654321")).hasMessageContaining("无效");
            String c=prefix+"-rate@example.com";
            for(int i=0;i<5;i++) guard.throttle("send",c,prefix);
            assertThatThrownBy(() -> guard.throttle("send",c,prefix)).hasMessageContaining("频繁");
            String d=prefix+"-expire@example.com"; guard.issue(d,"111111");
            // Advance only this challenge TTL, with no sleep and no modification of production keys.
            String key=(String)ReflectionTestUtils.invokeMethod(guard,"key",d);
            redis.expire(key,0,TimeUnit.SECONDS);
            assertThatThrownBy(() -> guard.consume(d,"111111")).hasMessageContaining("无效");
        } finally { pool.shutdownNow(); factory.destroy(); }
    }
}
