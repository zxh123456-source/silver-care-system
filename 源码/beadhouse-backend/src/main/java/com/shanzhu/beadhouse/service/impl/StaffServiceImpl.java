package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.common.util.RedisUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.OperateStaffQuery;
import com.shanzhu.beadhouse.entity.query.PageStaffByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.OperateStaffVo;
import com.shanzhu.beadhouse.entity.vo.PageStaffByKeyVo;
import com.shanzhu.beadhouse.service.StaffService;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import com.shanzhu.beadhouse.service.common.RoleFunc;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import com.shanzhu.beadhouse.common.config.security.handler.PasswordEncoderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

@Service
public class StaffServiceImpl implements StaffService {
    @Resource
    private RoleFunc roleFunc;
    @Resource
    private StaffMapper staffMapper;
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private PageUtil pageUtil;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private PasswordEncoderImpl passwordEncoder;
    @Resource
    private AttachmentAccessService attachmentAccessService;

    @Override
    public Result getRole() {
        return Result.success(BeanUtil.copyToList(roleFunc.listNotSuperAdminRole(), DropDown.class));
    }

    @Override
    public Result pageStaffByKey(PageStaffByKeyQuery query) {
        // 根据关键字查询员工列表
        List<PageStaffByKeyVo> pageStaffByKeyVoList = staffMapper.listStaffByKey(query);
        // 封装返回数据
        PageResult<PageStaffByKeyVo> pageResult = pageUtil.packPageResultData(pageStaffByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result addStaff(OperateStaffQuery query) {
        assertManageableRole(query.getRoleId());
        // 验证手机号是否已被注册
        AssertUtil.isNull(staffFunc.getStaffByAccount(query.getPhone()), ExceptionEnum.PHONE_REPEAT);
        // 验证邮箱是否已被注册
        AssertUtil.isNull(staffFunc.getStaffByAccount(query.getEmail()), ExceptionEnum.EMAIL_REPEAT);
        // 初始化员工
        Staff staff = editableProfile(query);
        staff.setPass(passwordEncoder.encode(query.getPhone().substring(5, 11)));
        staff.setLeaveFlag(YesNoEnum.NO.getCode());
        staff.setAvator("");
        // 新增
        staffMapper.insert(staff);
        Staff image = new Staff();
        image.setId(staff.getId());
        image.setAvator(storedImage(attachmentAccessService.bind("STAFF", staff.getId(), "", query.getAvator())));
        staffMapper.updateById(image);
        return Result.success();
    }

    @Override
    public Result getStaffById(Long staffId) {
        // 根据编号获取员工
        Staff staff = requireManageableStaff(staffId, false);
        // 转换实体
        OperateStaffVo operateStaffVo = BeanUtil.toBean(staff, OperateStaffVo.class);
        return Result.success(operateStaffVo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result editStaff(OperateStaffQuery query) {
        Staff stored = requireManageableStaff(query.getId(), true);
        assertManageableRole(query.getRoleId());
        // 验证手机号是否已被注册
        Staff staffByPhone = staffFunc.getStaffByAccount(query.getPhone());
        boolean checkPhone = staffByPhone != null && !Objects.equals(staffByPhone.getId(), query.getId());
        AssertUtil.notTrue(checkPhone, ExceptionEnum.PHONE_REPEAT);
        // 验证邮箱是否已被注册
        Staff staffByEmail = staffFunc.getStaffByAccount(query.getEmail());
        boolean checkEmail = staffByEmail != null && !Objects.equals(staffByEmail.getId(), query.getId());
        AssertUtil.notTrue(checkEmail, ExceptionEnum.EMAIL_REPEAT);
        // 封装修改
        Staff staff = editableProfile(query);
        staff.setId(stored.getId());
        String requestedImage = query.getAvator() == null ? stored.getAvator() : query.getAvator();
        staff.setAvator(storedImage(attachmentAccessService.bind("STAFF", stored.getId(), stored.getAvator(), requestedImage)));
        // 修改
        staffMapper.updateById(staff);
        // Role/profile edits must not leave the previous authorities active in the login cache.
        redisUtil.deleteObject(Constant.LOGIN_REDIS + query.getId());
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result leaveStaff(Long staffId) {
        requireManageableStaff(staffId, true);
        // 封装修改
        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setLeaveFlag(YesNoEnum.YES.getCode());
        // 修改
        staffMapper.updateById(staff);
        attachmentAccessService.retire("STAFF", staffId);
        // 删除该账号登录记录
        redisUtil.deleteObject(Constant.LOGIN_REDIS + staffId);
        return Result.success();
    }

    private Staff requireManageableStaff(Long staffId, boolean lock) {
        AssertUtil.notNull(staffId, ExceptionEnum.DATA_NOT_EXIST);
        Staff staff = lock ? staffMapper.selectByIdForUpdate(staffId) : staffMapper.selectById(staffId);
        AssertUtil.notNull(staff, ExceptionEnum.DATA_NOT_EXIST);
        assertManageableRole(staff.getRoleId());
        return staff;
    }

    private Staff editableProfile(OperateStaffQuery query) {
        Staff staff = new Staff();
        staff.setRoleId(query.getRoleId());
        staff.setName(query.getName());
        staff.setIdNum(query.getIdNum());
        staff.setAge(query.getAge());
        staff.setSex(query.getSex());
        staff.setPhone(query.getPhone());
        staff.setEmail(query.getEmail());
        staff.setAddress(query.getAddress());
        return staff;
    }

    private String storedImage(String url) {
        return url == null ? "" : url;
    }

    private void assertManageableRole(Long roleId) {
        // The staff list and role selector exclude reserved administrators; direct calls must too.
        AssertUtil.notTrue(Objects.equals(roleId, 1L), ExceptionEnum.AUTH_ERROR);
    }
}
