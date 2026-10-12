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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.controller.AccountController;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeExceptionAdvice;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.service.impl.AccountServiceImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;

/** Opt-in test against a dedicated ephemeral Redis; never uses the application database. */
@EnabledIfEnvironmentVariable(named="RESET_TEST_REDIS_PORT", matches="[0-9]+")
class PasswordResetGuardIntegrationTest {
    @Test void realRedisCooldownProducesIdenticalHttpResponsesForKnownAndUnknownEmails() throws Exception {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("RESET_TEST_REDIS_PORT")));
        JedisConnectionFactory factory = new JedisConnectionFactory(config); factory.afterPropertiesSet();
        try {
            PasswordResetGuard guard = new PasswordResetGuard();
            ReflectionTestUtils.setField(guard, "redis", new StringRedisTemplate(factory));
            StaffFunc staffFunc = mock(StaffFunc.class);
            PasswordResetDelivery delivery = mock(PasswordResetDelivery.class);
            String suffix = UUID.randomUUID().toString().replace("-", "") + "@example.com";
            String known = "known" + suffix, unknown = "unknown" + suffix;
            Staff staff = new Staff(); staff.setEmail(known);
            when(staffFunc.getStaffByAccount(known)).thenReturn(staff);
            AccountServiceImpl service = new AccountServiceImpl();
            ReflectionTestUtils.setField(service, "resetGuard", guard);
            ReflectionTestUtils.setField(service, "resetDelivery", delivery);
            ReflectionTestUtils.setField(service, "staffFunc", staffFunc);
            AccountController controller = new AccountController();
            ReflectionTestUtils.setField(controller, "accountService", service);
            MockMvc http = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new BusinessRuntimeExceptionAdvice()).build();
            ObjectMapper json = new ObjectMapper();
            for (int attempt = 0; attempt < 2; attempt++) {
                MvcResult a = http.perform(post("/account/sendCode").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("account", known)))).andReturn();
                MvcResult b = http.perform(post("/account/sendCode").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("account", unknown)))).andReturn();
                assertThat(b.getResponse().getStatus()).isEqualTo(a.getResponse().getStatus());
                JsonNode knownBody = json.readTree(a.getResponse().getContentAsString());
                JsonNode unknownBody = json.readTree(b.getResponse().getContentAsString());
                assertThat(knownBody.path("code").asInt()).isEqualTo(attempt == 0 ? 200 : 429);
                assertThat(unknownBody).isEqualTo(knownBody);
            }
            verify(delivery, times(1)).send(eq(known), matches("[0-9]{6}"));
            verify(delivery, never()).send(eq(unknown), anyString());
        } finally { factory.destroy(); }
    }
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
