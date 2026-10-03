package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.DateUtilWen;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.ConsultMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.po.Consult;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.query.GetConsultByConsultIdAndElderIdQuery;
import com.shanzhu.beadhouse.entity.query.OperateConsultQuery;
import com.shanzhu.beadhouse.entity.query.PageConsultByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetConsultByConsultIdAndElderIdVo;
import com.shanzhu.beadhouse.entity.vo.PageConsultByKeyVo;
import com.shanzhu.beadhouse.service.ConsultService;
import com.shanzhu.beadhouse.service.common.ConsultFunc;
import com.shanzhu.beadhouse.service.common.ElderFunc;
import com.shanzhu.beadhouse.service.common.SourceFunc;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

@Service
public class ConsultServiceImpl implements ConsultService {
    @Resource
    private ConsultMapper consultMapper;
    @Resource
    private ConsultFunc consultFunc;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private PageUtil pageUtil;
    @Resource
    private SourceFunc sourceFunc;
    @Resource
    private StaffFunc staffFunc;

    @Override
    public Result listConsultSource() {
        return Result.success(BeanUtil.copyToList(sourceFunc.listNotDelSource(null), DropDown.class));
    }

    @Override
    public Result listConsultStaff() {
        return Result.success(BeanUtil.copyToList(staffFunc.listStaffByRoleId(2L), DropDown.class));
    }

    @Override
    public Result pageConsultByKey(PageConsultByKeyQuery query) {
        // 获取开始/结束时间
        Date startTime = DateUtilWen.getDayStartTime(DateUtilWen.dateStrToDate(query.getStartTime()));
        Date endTime = DateUtilWen.getDayEndTime(DateUtilWen.dateStrToDate(query.getEndTime()));
        // 获取咨询列表
        List<PageConsultByKeyVo> consultByKeyVoList = consultMapper.listConsultByKey(query, startTime, endTime);
        // 封装返回数据
        PageResult<PageConsultByKeyVo> pageResult = pageUtil.packPageResultData(consultByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    @Transactional
    public Result addConsult(OperateConsultQuery query) {
        // 验证老人身份证号是否已存在
        AssertUtil.isNull(elderFunc.getElderByIdNum(query.getIdNum()), ExceptionEnum.ID_NUM_REPEAT);
        // 初始化老人
        Elder elder = elderFunc.operateConsultInitElder(query, true);
        // 新增老人
        elderMapper.insert(elder);
        // 初始化咨询人
        Consult consult = consultFunc.initConsult(query);
        consult.setElderId(elder.getId());
        // 新增咨询人
        consultMapper.insert(consult);
        return Result.success();
    }

    @Override
    public Result getConsultByConsultIdAndElderId(GetConsultByConsultIdAndElderIdQuery query) {
        // 根据咨询人编号和老人编号查询咨询信息
        List<GetConsultByConsultIdAndElderIdVo> getConsultByConsultIdAndElderId = consultMapper.getConsultByConsultIdAndElderId(query);
        // 判断是否为空
        AssertUtil.notNull(getConsultByConsultIdAndElderId, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(getConsultByConsultIdAndElderId);
    }

    @Override
    @Transactional
    public Result editConsult(OperateConsultQuery query) {
        // 验证老人
        elderFunc.checkElder(query.getElderId(), query.getIdNum());
        // 初始化老人
        Elder elder = elderFunc.operateConsultInitElder(query, false);
        elder.setId(query.getElderId());
        // 编辑老人
        elderMapper.updateById(elder);
        // 初始化咨询人
        Consult consult = consultFunc.initConsult(query);
        consult.setId(query.getConsultId());
        // 编辑咨询人
        consultMapper.updateById(consult);
        return Result.success();
    }

    @Override
    public Result deleteConsult(Long elderId) {
        // 验证老人
        Elder elder = elderFunc.checkElder(elderId, null);
        // 封装修改
        elder.setCheckFlag(CheckEnum.FAILURE.getStatus());
        // 修改
        elderMapper.updateById(elder);
        return Result.success();
    }

    @Override
    public Result intentionConsult(Long elderId) {
        // 验证老人
        Elder elder = elderFunc.checkElder(elderId, null);
        // 封装修改
        elder.setCheckFlag(CheckEnum.INTENTION.getStatus());
        // 修改
        elderMapper.updateById(elder);
        return Result.success();
    }
}
