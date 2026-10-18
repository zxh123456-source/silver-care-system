package com.shanzhu.beadhouse.common.config.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.common.config.security.filter.JwtAuthenticationTokenFilter;
import com.shanzhu.beadhouse.common.util.*;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.FilterChain;
import java.util.Collections;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import com.shanzhu.beadhouse.common.constant.Constant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SessionContractTest {
    private JwtAuthenticationTokenFilter filter;
    private RedisUtil redis;
    private CommonUtil common;
    private FilterChain chain;
    private StaffMapper staffMapper;
    private Staff active;
    @BeforeEach void setup() {
        filter=new JwtAuthenticationTokenFilter(); redis=mock(RedisUtil.class); common=mock(CommonUtil.class); chain=mock(FilterChain.class);
        ReflectionTestUtils.setField(filter,"redisUtil",redis); ReflectionTestUtils.setField(filter,"commonUtil",common);
        staffMapper=mock(StaffMapper.class); active=new Staff(); active.setId(4L); active.setRoleId(5L); active.setLeaveFlag("N");
        when(staffMapper.selectOne(any())).thenReturn(active); ReflectionTestUtils.setField(filter,"staffMapper",staffMapper);
        when(common.getIgnoreUrlArray()).thenReturn(new String[]{"/account/login"});
        when(common.existFlag(any(),anyString())).thenAnswer(invocation -> "/account/login".equals(invocation.getArgument(1)));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private MockHttpServletResponse request(String path,String token) throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("GET",path);
        if(token!=null) request.addHeader("token",token);
        MockHttpServletResponse response=new MockHttpServletResponse(); filter.doFilter(request,response,chain); return response;
    }
    private LoginUserVo session(String token) {
        LoginUserVo user=new LoginUserVo(); user.setId(4L); user.setRoleId(5L); user.setToken(token);
        user.setAuthUrlList(Collections.singletonList("/ai/daily/index")); return user;
    }
    @Test void publicEndpointsIgnoreEvenMalformedStaleTokens() throws Exception {
        MockHttpServletResponse response=request("/account/login","broken-old-token");
        assertThat(response.getStatus()).isEqualTo(200); verify(chain).doFilter(any(),any()); verifyNoInteractions(redis);
    }
    @Test void missingTokenReturnsReal401WithoutReadingRedis() throws Exception {
        MockHttpServletResponse response=request("/ai/daily/overview",null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).path("code").asInt()).isEqualTo(401);
        verifyNoInteractions(redis,chain);
    }
    @Test void missingSessionAndMalformedCachedPayloadFailAs401() throws Exception {
        String token=JwtUtil.createJwt("4");
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401);
        when(redis.getCacheObject("login:4")).thenReturn("invalid cached object");
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401); verifyNoInteractions(chain);
    }
    @Test void oldTokenCannotBecomeValidAgainAfterAnotherLogin() throws Exception {
        String old=JwtUtil.createJwt("4"), current=JwtUtil.createJwt("4");
        assertThat(current).isNotEqualTo(old);
        when(redis.getCacheObject("login:4")).thenReturn(session(current));
        assertThat(request("/ai/daily/overview",old).getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
        assertThat(request("/ai/daily/overview",current).getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(),any());
    }
    @Test void malformedSessionIdentityAndAuthoritiesCannotAuthenticate() throws Exception {
        String token=JwtUtil.createJwt("4"); LoginUserVo user=session(token); user.setId(9L);
        when(redis.getCacheObject("login:4")).thenReturn(user);
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401);
        user.setId(4L); user.setAuthUrlList(null);
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
    @Test void redisOutageReturns503InsteadOfMisclassifyingCredentials() throws Exception {
        when(redis.getCacheObject("login:4")).thenThrow(new IllegalStateException("test Redis outage"));
        assertThat(request("/ai/daily/overview",JwtUtil.createJwt("4")).getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
    }
    @Test void loginJsonNeverExposesPasswordHash() throws Exception {
        LoginUserVo user=session("test-token"); user.setPass("not-for-api-response");
        assertThat(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(user)).has("pass")).isFalse();
    }
    @Test void inactiveOrChangedDatabaseRoleRejectsTheCachedSession() throws Exception {
        String token=JwtUtil.createJwt("4"); when(redis.getCacheObject("login:4")).thenReturn(session(token));
        active.setRoleId(7L);
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401);
        active.setRoleId(5L); active.setLeaveFlag("Y");
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
    @Test void databaseOutageReturns503WithoutGrantingAccess() throws Exception {
        String token=JwtUtil.createJwt("4"); when(redis.getCacheObject("login:4")).thenReturn(session(token));
        when(staffMapper.selectOne(any())).thenThrow(new IllegalStateException("test database outage"));
        assertThat(request("/ai/daily/overview",token).getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
    }
    @Test void expiredAndForeignIssuerTokensFailBeforeRedisLookup() throws Exception {
        String expired=Jwts.builder().setIssuer(Constant.TOKEN_ISSURE).setSubject(Constant.TOKEN_SUBJECT)
                .claim("id","4").setExpiration(new Date(System.currentTimeMillis()-60000))
                .signWith(Keys.hmacShaKeyFor(Constant.TOKEN_SECRET.getBytes(StandardCharsets.UTF_8)),SignatureAlgorithm.HS256).compact();
        String foreign=Jwts.builder().setIssuer("another-application").setSubject(Constant.TOKEN_SUBJECT)
                .claim("id","4").setExpiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(Constant.TOKEN_SECRET.getBytes(StandardCharsets.UTF_8)),SignatureAlgorithm.HS256).compact();
        assertThat(request("/ai/daily/overview",expired).getStatus()).isEqualTo(401);
        assertThat(request("/ai/daily/overview",foreign).getStatus()).isEqualTo(401);
        verifyNoInteractions(redis,chain);
    }
}
