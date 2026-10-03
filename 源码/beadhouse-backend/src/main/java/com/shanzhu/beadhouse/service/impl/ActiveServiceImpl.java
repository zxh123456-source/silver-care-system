package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.DateUtilWen;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.ActiveMapper;
import com.shanzhu.beadhouse.dao.mapper.ActiveParticipantMapper;
import com.shanzhu.beadhouse.entity.po.Active;
import com.shanzhu.beadhouse.entity.query.OperateActiveQuery;
import com.shanzhu.beadhouse.entity.query.PageActiveByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetActiveByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageActiveByKeyVo;
import com.shanzhu.beadhouse.service.ActiveService;
import com.shanzhu.beadhouse.service.common.ActiveFunc;
import com.shanzhu.beadhouse.service.common.ActiveParticipantFunc;
import com.shanzhu.beadhouse.service.common.ActiveTypeFunc;
import com.shanzhu.beadhouse.service.common.CommonFunc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

@Service
public class ActiveServiceImpl implements ActiveService {
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private ActiveTypeFunc activeTypeFunc;
    @Resource
    private ActiveMapper activeMapper;
    @Resource
    private ActiveParticipantMapper activeParticipantMapper;
    @Resource
    private ActiveParticipantFunc activeParticipantFunc;
    @Resource
    private ActiveFunc activeFunc;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result getActiveType() {
        return Result.success(BeanUtil.copyToList(activeTypeFunc.listNotDelActiveType(null), DropDown.class));
    }

    @Override
    public Result pageActiveByKey(PageActiveByKeyQuery query) {
        // 获取开始/结束时间
        Date startTime = DateUtilWen.getDayStartTime(DateUtilWen.dateStrToDate(query.getStartTime()));
        Date endTime = DateUtilWen.getDayEndTime(DateUtilWen.dateStrToDate(query.getEndTime()));
        // 根据关键词查询活动
        List<PageActiveByKeyVo> pageActiveByKeyVoList = activeMapper.listActiveByKey(query, startTime, endTime);
        // 封装返回数据
        PageResult<PageActiveByKeyVo> pageResult = pageUtil.packPageResultData(pageActiveByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    public Result pageSearchElderByKey(PageSearchElderByKeyQuery query) {
        List<String> checkFlagList = new ArrayList<>(Arrays.asList(CheckEnum.CONSULT.getStatus(), CheckEnum.INTENTION.getStatus(), CheckEnum.RESERVE.getStatus(), CheckEnum.ENTER.getStatus(), CheckEnum.EXIT_AUDIT.getStatus(), CheckEnum.EXIT.getStatus()));
        // 根据姓名和联系电话获取咨询中、意向跟进、预定、入住、退住审核、已退住老人列表
        return commonFunc.pageSearchElderByKeyResult(query, checkFlagList);
    }

    @Override
    @Transactional
    public Result addActive(OperateActiveQuery query) {
        // 验证活动名称是否相同
        AssertUtil.isNull(activeFunc.getActiveByName(query.getName()), ExceptionEnum.ACTIVE_REPEAT);
        // 初始化活动
        query.setId(null);
        Active active = BeanUtil.toBean(query, Active.class);
        active.setDelFlag(YesNoEnum.NO.getCode());
        // 新增
        activeMapper.insert(active);
        // 批量插入活动参与者
        activeParticipantFunc.saveBatchActiveParticipant(query.getElderIdList(), active.getId(), false);
        return Result.success();
    }

    @Override
    public Result getActiveById(Long activeId) {
        // 根据编号获取活动
        Active active = activeMapper.selectById(activeId);
        // 判断是否为空
        AssertUtil.notNull(active, ExceptionEnum.DATA_NOT_EXIST);
        // 转换实体
        GetActiveByIdVo getActiveByIdVo = BeanUtil.toBean(active, GetActiveByIdVo.class);
        // 根据活动编号获取活动参与老人列表并设值
        getActiveByIdVo.setParticipateElderVoList(activeParticipantMapper.listParticipateElder(activeId));
        return Result.success(getActiveByIdVo);
    }

    @Override
    @Transactional
    public Result editActive(OperateActiveQuery query) {
        // 验证活动名称是否相同
        Active activeByName = activeFunc.getActiveByName(query.getName());
        boolean checkName = activeByName != null && !Objects.equals(activeByName.getId(), query.getId());
        AssertUtil.notTrue(checkName, ExceptionEnum.ACTIVE_REPEAT);
        // 封装修改
        Active active = BeanUtil.toBean(query, Active.class);
        // 修改
        activeMapper.updateById(active);
        // 批量插入活动参与者
        activeParticipantFunc.saveBatchActiveParticipant(query.getElderIdList(), active.getId(), true);
        return Result.success();
    }

    @Override
    public Result deleteActive(Long activeId) {
        // 封装修改
        Active active = new Active();
        active.setId(activeId);
        active.setDelFlag(YesNoEnum.YES.getCode());
        // 修改
        activeMapper.updateById(active);
        return Result.success();
    }
}
