package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.util.ReUtil;
import com.shanzhu.beadhouse.common.config.security.entity.LoginUserDetails;
import com.shanzhu.beadhouse.common.config.security.handler.PasswordEncoderImpl;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.*;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.EditQuery;
import com.shanzhu.beadhouse.entity.query.ForgetQuery;
import com.shanzhu.beadhouse.entity.query.LoginQuery;
import com.shanzhu.beadhouse.entity.query.SendCodeQuery;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import com.shanzhu.beadhouse.service.AccountService;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import com.shanzhu.beadhouse.service.common.PasswordResetGuard;
import com.shanzhu.beadhouse.service.common.PasswordResetDelivery;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AccountServiceImpl implements AccountService {
    @Resource
    private AuthenticationManager authenticationManager;
    @Resource
    private AuthorityAssert authorityAssert;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private StaffMapper staffMapper;
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private PasswordEncoderImpl passwordEncoder;
    @Resource private PasswordResetGuard resetGuard;
    @Resource private PasswordResetDelivery resetDelivery;
    private final SecureRandom resetRandom = new SecureRandom();

    @Override
    public Result login(LoginQuery query) {
        // AuthenticationManager进行用户认证
        // 将登录数据封装为UsernamePasswordAuthenticationToken对象
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(query.getPhone(), query.getPass());
        // 将封装对象传入进行认证
        Authentication authenticate = authenticationManager.authenticate(authenticationToken);
        // 认证通过，获取登录用户信息
        LoginUserDetails loginUserDetails = (LoginUserDetails) authenticate.getPrincipal();
        LoginUserVo loginUserVo = loginUserDetails.getLoginUserVo();
        // 兼容历史 AES 密文；成功认证后立即升级为 BCrypt。
        if (passwordEncoder.isLegacyEncoded(loginUserVo.getPass())) {
            String upgradedPassword = passwordEncoder.encode(query.getPass());
            Staff upgradedStaff = new Staff();
            upgradedStaff.setId(loginUserVo.getId());
            upgradedStaff.setPass(upgradedPassword);
            staffMapper.updateById(upgradedStaff);
            loginUserVo.setPass(upgradedPassword);
        }
        // 使用用户id生成jwt，并设置到登录用户信息中
        String idStr = loginUserVo.getId().toString();
        loginUserVo.setToken(JwtUtil.createJwt(idStr));
        // 用户id作为key，将登录用户信息存入redis
        redisUtil.setCacheObject(Constant.LOGIN_REDIS + idStr, loginUserVo, Constant.EXPIRE_TIME, TimeUnit.MILLISECONDS);
        return Result.success(loginUserVo);
    }

    @Override
    public Result sendCode(SendCodeQuery query) {
        String account = query == null ? "" : normalizeAccount(query.getAccount());
        if (!ReUtil.isMatch(Constant.EMAIL_REGULAR, account))
            return Result.error(400, "请使用已登记邮箱找回密码；手机号找回请联系管理员");
        resetGuard.throttle("send", account, clientIp());
        resetDelivery.requireAvailable();
        Staff staff = staffFunc.getStaffByAccount(account);
        if (staff != null && account.equals(staff.getEmail())) {
            String code = String.format("%06d", resetRandom.nextInt(1000000));
            resetGuard.issue(account, code);
            try { resetDelivery.send(staff.getEmail(), code); }
            catch (RuntimeException failure) { resetGuard.discard(account); throw failure; }
        }
        return Result.success("如果邮箱已登记，验证码将发送至该邮箱");
    }

    @Override
    public Result forget(ForgetQuery query) {
        String account = query == null ? "" : normalizeAccount(query.getAccount());
        if (query == null || !ReUtil.isMatch(Constant.EMAIL_REGULAR, account)
                || query.getPass() == null || query.getPass().length() < 8 || query.getPass().length() > 64
                || query.getPass().getBytes(StandardCharsets.UTF_8).length > 72)
            return Result.error(400, "请填写已登记邮箱和8至64位新密码（UTF-8不超过72字节）");
        resetGuard.throttle("verify", account, clientIp());
        resetGuard.consume(account, query.getCode() == null ? "" : query.getCode());
        Staff staff = staffFunc.getStaffByAccount(account);
        if (staff == null || !account.equals(staff.getEmail())) return Result.error(400, "验证码无效或已过期，请重新申请");
        staff.setPass(passwordEncoder.encode(query.getPass()));
        staffMapper.updateById(staff);
        redisUtil.deleteObject(Constant.LOGIN_REDIS + staff.getId());
        return Result.success();
    }

    private String normalizeAccount(String account) { return account == null ? "" : account.trim(); }

    private String clientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        // Do not trust caller-controlled X-Forwarded-For headers.
        return attributes == null ? "local" : attributes.getRequest().getRemoteAddr();
    }

    @Override
    public Result edit(EditQuery query) {
        // 获取登录用户信息
        LoginUserVo loginUserInfo = authorityAssert.getLoginUserInfo();
        // 原密码错误
        boolean checkOldPass = passwordEncoder.matches(query.getOldPass(), loginUserInfo.getPass());
        AssertUtil.isTrue(checkOldPass, ExceptionEnum.OLD_PASS_ERROR);
        // 新密码与原密码相同
        boolean checkPass = passwordEncoder.matches(query.getNewPass(), loginUserInfo.getPass());
        AssertUtil.notTrue(checkPass, ExceptionEnum.PASS_SAME);
        // 封装修改密码
        Staff staff = new Staff();
        staff.setId(loginUserInfo.getId());
        staff.setPass(passwordEncoder.encode(query.getNewPass()));
        // 修改
        staffMapper.updateById(staff);
        // 删除redis登录信息
        redisUtil.deleteObject(Constant.LOGIN_REDIS + staff.getId());
        return Result.success();
    }

    @Override
    public Result logout() {
        // 删除redis中的值
        redisUtil.deleteObject(Constant.LOGIN_REDIS + authorityAssert.getLoginUserId());
        return Result.success();
    }
}
