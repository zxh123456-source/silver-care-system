package com.shanzhu.beadhouse.common.config.security.service;

import cn.hutool.core.bean.BeanUtil;
import com.shanzhu.beadhouse.common.config.security.entity.LoginUserDetails;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.dao.mapper.AuthMapper;
import com.shanzhu.beadhouse.entity.po.Auth;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义数据库查询
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private AuthMapper authMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 查询基本信息
        Staff staff = staffFunc.getStaffByAccount(username);
        // 未查询到数据
        AssertUtil.notNull(staff, ExceptionEnum.CERTIFICATION_ERROR);
        // 查询权限信息
        List<Long> authIdList = new ArrayList<>();
        List<String> authUrlList = new ArrayList<>();
        for (Auth auth : authMapper.listAuthByRoleId(staff.getRoleId())) {
            authIdList.add(auth.getId());
            authUrlList.add(auth.getUrl());
        }
        // 把数据封装成UserDetails返回
        LoginUserVo loginUserVo = BeanUtil.toBean(staff, LoginUserVo.class);
        loginUserVo.setAuthIdList(authIdList);
        loginUserVo.setAuthUrlList(authUrlList);
        return new LoginUserDetails(loginUserVo);
    }
}
