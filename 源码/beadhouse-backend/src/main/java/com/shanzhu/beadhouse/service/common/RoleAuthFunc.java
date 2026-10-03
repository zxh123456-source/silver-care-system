package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanzhu.beadhouse.dao.mapper.RoleAuthMapper;
import com.shanzhu.beadhouse.entity.po.RoleAuth;
import org.springframework.stereotype.Component;

/**
 * 角色权限表公共方法
 */
@Component
public class RoleAuthFunc extends ServiceImpl<RoleAuthMapper, RoleAuth> {
}
