package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shanzhu.beadhouse.common.constant.BedEnum;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.DateUtilWen;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.OperateCheckContractQuery;
import com.shanzhu.beadhouse.entity.query.PageCheckContractByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetCheckContractByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageCheckContractByKeyVo;
import com.shanzhu.beadhouse.service.CheckContractService;
import com.shanzhu.beadhouse.service.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import java.util.Map;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class CheckContractServiceImpl implements CheckContractService {
    @Resource
    private com.shanzhu.beadhouse.service.common.AiDataScopeService dataScopeService;
    @Resource private BedLifecycleSupport lifecycle;
    @Resource private ReserveFunc reserveFunc;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private BedMapper bedMapper;
    @Resource
    private ReserveMapper reserveMapper;
    @Resource
    private BedFunc bedFunc;
    @Resource
    private ContractMapper contractMapper;
    @Resource
    private ContractFunc contractFunc;
    @Resource
    private NurseGradeFunc nurseGradeFunc;
    @Resource
    private CateringSetFunc cateringSetFunc;
    @Resource
    private EmergencyContactMapper emergencyContactMapper;
    @Resource
    private EmergencyContactFunc emergencyContactFunc;
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageCheckContractByKey(PageCheckContractByKeyQuery query) {
        List<String> checkFlagList = new ArrayList<>(Arrays.asList(CheckEnum.ENTER.getStatus(), CheckEnum.EXIT_AUDIT.getStatus()));
        // 根据条件获取老人信息
        List<Elder> listElderByKey = elderFunc.listElderByKey(query.getName(), query.getSex(), query.getIdNum(), checkFlagList);
        // 实体转换
        List<PageCheckContractByKeyVo> pageCheckContractByKeyVoList = BeanUtil.copyToList(listElderByKey, PageCheckContractByKeyVo.class);
        // 封装返回数据
        PageResult<PageCheckContractByKeyVo> pageResult = pageUtil.packPageResultData(pageCheckContractByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    public Result pageSearchElderByKey(PageSearchElderByKeyQuery query) {
        List<String> checkFlagList = new ArrayList<>(Arrays.asList(CheckEnum.CONSULT.getStatus(), CheckEnum.INTENTION.getStatus(), CheckEnum.RESERVE.getStatus(), CheckEnum.EXIT.getStatus()));
        // 根据姓名和联系电话获取咨询中、意向跟进、预定、已退住老人列表
        return commonFunc.pageSearchElderByKeyResult(query, checkFlagList);
    }

    @Override
    public Result listNurseGrade() {
        // 获取未被删除的护理等级列表
        List<NurseGrade> nurseGradeList = nurseGradeFunc.listNotDelNurseGrade();
        // 转换实体
        List<DropDown> dropDownList = BeanUtil.copyToList(nurseGradeList, DropDown.class);
        return Result.success(dropDownList);
    }

    @Override
    public Result listCateringSet() {
        // 获取未被删除的餐饮套餐列表
        List<CateringSet> cateringSetList = cateringSetFunc.listNotDelCateringSet();
        // 转换实体
        List<DropDown> dropDownList = BeanUtil.copyToList(cateringSetList, DropDown.class);
        return Result.success(dropDownList);
    }

    @Override
    public Result getBuildTree() {
        return commonFunc.getBuildingTreeResult(false);
    }

    @Override
    public Result getBedById(Long bedId) {
        return Result.success(bedMapper.getBedById(bedId));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result addCheckContract(OperateCheckContractQuery query) {
        AssertUtil.notNull(query.getBedId(), ExceptionEnum.PARAM_ERROR);
        if (query.getIdNum() == null || query.getIdNum().trim().isEmpty())
            throw new BusinessRuntimeException(400, "身份证号不能为空");
        Elder existing = elderFunc.getElderByIdNum(query.getIdNum());
        Elder elder;
        if (existing == null) {
            elder = BeanUtil.toBean(query, Elder.class);
            elder.setId(null);
            elder.setBedId(null);
            elder.setBalance(BigDecimal.ZERO);
            elder.setCheckFlag(CheckEnum.CONSULT.getStatus());
            elderMapper.insert(elder);
        } else {
            dataScopeService.assertElderAccess(existing.getId());
            elder = lifecycle.lockElder(existing.getId());
            dataScopeService.assertElderAccess(elder.getId());
            if (!Objects.equals(elder.getIdNum(), query.getIdNum()))
                throw new BusinessRuntimeException(409, "老人身份已变化，请刷新后重试");
        }
        lifecycle.assertState(elder, CheckEnum.CONSULT.getStatus(), CheckEnum.INTENTION.getStatus(),
                CheckEnum.RESERVE.getStatus(), CheckEnum.EXIT.getStatus());
        Map<Long, Bed> beds = lifecycle.lockBeds(elder.getBedId(), query.getBedId());
        Bed target = lifecycle.requireBed(beds, query.getBedId());
        Reserve reservation = reserveMapper.selectActiveByElderForUpdate(elder.getId());
        Bed previous = null;
        if (CheckEnum.RESERVE.getStatus().equals(elder.getCheckFlag())) {
            previous = lifecycle.requireBed(beds, elder.getBedId());
            lifecycle.assertReservationBinding(elder, reservation, previous);
            reserveFunc.assertUnexpired(reservation);
        } else if (elder.getBedId() != null || reservation != null) {
            throw new BusinessRuntimeException(409, "老人仍有床位或有效预订，请核对后重试");
        }
        if (previous == null || !Objects.equals(previous.getId(), target.getId()))
            lifecycle.assertBedState(target, BedEnum.IDLE.getStatus());
        if (reservation != null) {
            reservation.setState(Reserve.CONVERTED);
            reserveMapper.updateById(reservation);
        }
        if (previous != null && !Objects.equals(previous.getId(), target.getId())) lifecycle.releaseBed(previous);
        BeanUtil.copyProperties(query, elder, "id");
        elder.setCheckFlag(CheckEnum.ENTER.getStatus());
        elderMapper.updateById(elder);
        lifecycle.setBedState(target, BedEnum.ENTER.getStatus());
        emergencyContactFunc.saveBatchEmergencyContact(query, elder.getId(), false);
        contractFunc.insertContract(query, elder.getId());
        return Result.success();
    }

    @Override
    public Result getCheckContractById(Long elderId) {
        dataScopeService.assertElderAccess(elderId);
        // 根据编号获取老人
        Elder elder = elderMapper.selectById(elderId);
        // 验证老人是否存在
        AssertUtil.notNull(elder, ExceptionEnum.DATA_NOT_EXIST);
        // 实体转换
        GetCheckContractByIdVo getCheckContractByIdVo = BeanUtil.toBean(elder, GetCheckContractByIdVo.class);
        // 根据老人编号获取合同
        Contract contract = contractMapper.selectOne(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getElderId, elderId));
        if (ObjUtil.isNotEmpty(contract)) {
            getCheckContractByIdVo.setStaffId(contract.getStaffId());
            getCheckContractByIdVo.setSignDate(DateUtilWen.dateToDateStr(contract.getSignDate(), "yyyy/MM/dd hh:mm:ss"));
            getCheckContractByIdVo.setStartDate(DateUtilWen.dateToDateStr(contract.getStartDate(), "yyyy/MM/dd hh:mm:ss"));
            getCheckContractByIdVo.setEndDate(DateUtilWen.dateToDateStr(contract.getEndDate(), "yyyy/MM/dd hh:mm:ss"));
        }
        // 设置老人的紧急联系人列表
        getCheckContractByIdVo.setOperateEmergencyContactQueryList(emergencyContactFunc.listEmgencyContactByElderId(elderId));
        return Result.success(getCheckContractByIdVo);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result editCheckContract(OperateCheckContractQuery query) {
        dataScopeService.assertElderAccess(query.getId());
        Elder elder = lifecycle.lockElder(query.getId());
        dataScopeService.assertElderAccess(elder.getId());
        lifecycle.assertState(elder, CheckEnum.ENTER.getStatus());
        AssertUtil.notNull(query.getBedId(), ExceptionEnum.PARAM_ERROR);
        if (query.getIdNum() == null || query.getIdNum().trim().isEmpty())
            throw new BusinessRuntimeException(400, "身份证号不能为空");
        Map<Long, Bed> beds = lifecycle.lockBeds(elder.getBedId(), query.getBedId());
        Bed oldBed = lifecycle.requireBed(beds, elder.getBedId());
        Bed target = lifecycle.requireBed(beds, query.getBedId());
        lifecycle.assertBedState(oldBed, BedEnum.ENTER.getStatus());
        if (!Objects.equals(oldBed.getId(), target.getId())) {
            lifecycle.assertBedState(target, BedEnum.IDLE.getStatus());
            lifecycle.releaseBed(oldBed);
            lifecycle.setBedState(target, BedEnum.ENTER.getStatus());
        }
        BeanUtil.copyProperties(query, elder, "id");
        elderMapper.updateById(elder);
        emergencyContactFunc.saveBatchEmergencyContact(query, elder.getId(), true);
        contractFunc.insertContract(query, elder.getId());
        return Result.success();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result deleteCheckContract(Long elderId) {
        dataScopeService.assertElderAccess(elderId);
        Elder elder = lifecycle.lockElder(elderId);
        dataScopeService.assertElderAccess(elder.getId());
        // Active occupation must go through the explicit checkout approval workflow.
        lifecycle.assertState(elder, CheckEnum.EXIT.getStatus());
        if (elder.getBedId() != null)
            throw new BusinessRuntimeException(409, "老人仍有关联床位，不能失效合同");
        elderMapper.deleteElderById(elderId);
        return Result.success();
    }
}
