package com.shanzhu.beadhouse.common.config.security.filter;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.*;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import com.shanzhu.beadhouse.entity.base.Result;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private CommonUtil commonUtil;
    @Resource
    private StaffMapper staffMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 获取jwt
        String jwt = request.getHeader("token");
        // 获取请求路径
        String requestURI = request.getRequestURI();
        // 验证url是否在白名单之内
        boolean ignore = commonUtil.existFlag(commonUtil.getIgnoreUrlArray(), requestURI);
        // Public login/reset endpoints must remain usable with an expired token in the browser.
        if (ignore) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!StringUtils.hasText(jwt)) {
            ResponseUtil.exceptionEnumOut(response, ExceptionEnum.ACCESS_ERROR);
            return;
        }
        // jwt不为空 且 url未在白名单之内
        // 解析jwt获取用户id
        String id;
        try {
            Jws<Claims> claimsJws = JwtUtil.parseJwt(jwt);
            id = claimsJws.getBody().get("id").toString();
            if (!id.matches("[1-9][0-9]*") || Long.parseLong(id) <= 0) throw new IllegalArgumentException("Invalid subject id");
        } catch (Exception e) {
            // token解析出错（非法token/token过期）
            ResponseUtil.exceptionEnumOut(response, ExceptionEnum.TOKEN_ERROR);
            return;
        }
        // 从redis获取用户信息
        Object cached;
        try { cached = redisUtil.getCacheObject(Constant.LOGIN_REDIS + id); }
        catch (RuntimeException failure) {
            log.warn("Session store unavailable ({})", failure.getClass().getSimpleName());
            ResponseUtil.resultOut(response, Result.error(503,"登录服务暂不可用，请稍后重试"));
            return;
        }
        if (!(cached instanceof LoginUserVo)) {
            ResponseUtil.exceptionEnumOut(response, ExceptionEnum.TOKEN_ERROR);
            return;
        }
        LoginUserVo loginUserVo = (LoginUserVo) cached;
        if (!Long.valueOf(id).equals(loginUserVo.getId()) || loginUserVo.getToken() == null
                || !MessageDigest.isEqual(jwt.getBytes(StandardCharsets.UTF_8), loginUserVo.getToken().getBytes(StandardCharsets.UTF_8))
                || loginUserVo.getAuthUrlList() == null || loginUserVo.getAuthUrlList().stream().anyMatch(value -> !StringUtils.hasText(value))) {
            ResponseUtil.exceptionEnumOut(response, ExceptionEnum.TOKEN_ERROR);
            return;
        }
        // Do not keep granting an old role when a committed staff change missed cache invalidation.
        Staff current;
        try {
            current = staffMapper.selectOne(new QueryWrapper<Staff>().select("id","role_id","leave_flag").eq("id",loginUserVo.getId()));
        } catch (RuntimeException failure) {
            log.warn("Account state unavailable ({})", failure.getClass().getSimpleName());
            ResponseUtil.resultOut(response, Result.error(503,"登录服务暂不可用，请稍后重试"));
            return;
        }
        if (current == null || !"N".equalsIgnoreCase(current.getLeaveFlag())
                || !Objects.equals(current.getRoleId(),loginUserVo.getRoleId())) {
            ResponseUtil.exceptionEnumOut(response, ExceptionEnum.TOKEN_ERROR);
            return;
        }
        // 获取权限列表并转换
        List<SimpleGrantedAuthority> authorityList = loginUserVo.getAuthUrlList().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        // 认证
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(loginUserVo, null, authorityList);
        // 存入上下文
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        // 放行
        filterChain.doFilter(request, response);
    }
}
