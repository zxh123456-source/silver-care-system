package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.util.ObjUtil;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shanzhu.beadhouse.common.constant.BedEnum;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.DateUtilWen;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.BedMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.dao.mapper.ReserveMapper;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.GetReserveByReserveIdAndElderIdQuery;
import com.shanzhu.beadhouse.entity.query.AddReserveQuery;
import com.shanzhu.beadhouse.entity.query.PageReserveByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.ExpireReserveVo;
import com.shanzhu.beadhouse.entity.vo.GetReserveByReserveIdAndElderIdVo;
import com.shanzhu.beadhouse.entity.vo.PageReserveByKeyVo;
import com.shanzhu.beadhouse.service.ReserveService;
import com.shanzhu.beadhouse.service.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.*;

@Service
public class ReserveServiceImpl implements ReserveService {
    @Resource private AiDataScopeService dataScopeService;
    @Resource private BedLifecycleSupport lifecycle;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private BuildingFunc buildingFunc;
    @Resource
    private FloorFunc floorFunc;
    @Resource
    private RoomFunc roomFunc;
    @Resource
    private BedFunc bedFunc;
    @Resource
    private BedMapper bedMapper;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private ReserveMapper reserveMapper;
    @Resource
    private ReserveFunc reserveFunc;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageReserveByKey(PageReserveByKeyQuery query) {
        // 根据条件获取老人信息
        List<PageReserveByKeyVo> pageReserveByKeyVoList = reserveMapper.listReserveByKey(query, dataScopeService.queryStaffId());
        // 封装返回数据
        PageResult<PageReserveByKeyVo> pageResult = pageUtil.packPageResultData(pageReserveByKeyVoList, query.getPageNum(), query.getPageSize());
        // 替换退款状态
        pageResult.getList().forEach(pageReserveByKeyVo -> pageReserveByKeyVo.setReserveFlag(
                Objects.equals(pageReserveByKeyVo.getReserveFlag(), YesNoEnum.NO.getCode()) ?
                        "未退款" :
                        "已退款"
        ));
        return Result.success(pageResult);
    }

    @Override
    public Result pageSearchElderByKey(PageSearchElderByKeyQuery query) {
        List<String> checkFlagList = new ArrayList<>(Arrays.asList(CheckEnum.CONSULT.getStatus(), CheckEnum.INTENTION.getStatus(), CheckEnum.EXIT.getStatus()));
        // 根据姓名和联系电话获取咨询中、意向跟进、已退住老人列表
        return commonFunc.pageSearchElderByKeyResult(query, checkFlagList);
    }

    @Override
    public Result getBuildTree() {
        return commonFunc.getBuildingTreeResult(true);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result addReserve(AddReserveQuery query) {
        AssertUtil.notNull(query.getBedId(), ExceptionEnum.PARAM_ERROR);
        if (query.getIdNum() == null || query.getIdNum().trim().isEmpty())
            throw new BusinessRuntimeException(400, "身份证号不能为空");
        Reserve reserve = reserveFunc.operateReserveInitElder(query, null);
        reserveFunc.assertUnexpired(reserve);
        Elder existing = elderFunc.getElderByIdNum(query.getIdNum());
        Elder elder;
        if (existing == null) {
            // Establish the unique identity before taking a bed lock. Roll back this row on any later failure.
            elder = elderFunc.operateReserveInitElder(query, true);
            elder.setBedId(null);
            elder.setCheckFlag(CheckEnum.CONSULT.getStatus());
            elderMapper.insert(elder);
        } else {
            dataScopeService.assertElderAccess(existing.getId());
            elder = lifecycle.lockElder(existing.getId());
            dataScopeService.assertElderAccess(elder.getId());
            if (!Objects.equals(elder.getIdNum(), query.getIdNum()))
                throw new BusinessRuntimeException(409, "老人身份已变化，请刷新后重试");
        }
        lifecycle.assertState(elder, CheckEnum.CONSULT.getStatus(), CheckEnum.INTENTION.getStatus(), CheckEnum.EXIT.getStatus());
        Map<Long, Bed> beds = lifecycle.lockBeds(elder.getBedId(), query.getBedId());
        Bed bed = lifecycle.requireBed(beds, query.getBedId());
        lifecycle.assertBedState(bed, BedEnum.IDLE.getStatus());
        if (elder.getBedId() != null || reserveMapper.selectActiveByElderForUpdate(elder.getId()) != null)
            throw new BusinessRuntimeException(409, "老人仍有床位或有效预订，请核对后重试");
        Elder change = elderFunc.operateReserveInitElder(query, false);
        change.setId(elder.getId());
        elderMapper.updateById(change);
        reserve.setElderId(elder.getId());
        reserveMapper.insert(reserve);
        lifecycle.setBedState(bed, BedEnum.RESERVE.getStatus());
        return Result.success();
    }

    @Override
    public Result getReserveByReserveIdAndElderId(GetReserveByReserveIdAndElderIdQuery query) {
        AssertUtil.notNull(query.getReserveId(), ExceptionEnum.DATA_NOT_EXIST);
        Reserve stored = reserveMapper.selectById(query.getReserveId());
        AssertUtil.notNull(stored, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(stored.getElderId());
        if (!Objects.equals(stored.getElderId(), query.getElderId())) throw new BusinessRuntimeException(403, "预订记录与老人不匹配");
        // 根据编号获取预定
        GetReserveByReserveIdAndElderIdVo getReserveByReserveIdAndElderIdVo = reserveMapper.getReserveByReserveIdAndElderId(query);
        // 判断是否为空
        AssertUtil.notNull(getReserveByReserveIdAndElderIdVo, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(getReserveByReserveIdAndElderIdVo);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result refund(Long reserveId) {
        AssertUtil.notNull(reserveId, ExceptionEnum.PARAM_ERROR);
        Reserve locator = reserveMapper.selectById(reserveId);
        AssertUtil.notNull(locator, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(locator.getElderId());
        Elder elder = lifecycle.lockElder(locator.getElderId());
        dataScopeService.assertElderAccess(elder.getId());
        Map<Long, Bed> beds = lifecycle.lockBeds(elder.getBedId(), locator.getBedId());
        Reserve reserve = reserveMapper.selectByIdForUpdate(reserveId);
        AssertUtil.notNull(reserve, ExceptionEnum.DATA_NOT_EXIST);
        if (!Objects.equals(reserve.getElderId(), elder.getId()))
            throw new BusinessRuntimeException(409, "预订归属已变化，请刷新后重试");
        // A replay of a completed refund never touches the resident's later bed.
        if (Reserve.REFUNDED.equals(reserve.getState())) return Result.success();
        Bed bed = lifecycle.requireBed(beds, reserve.getBedId());
        lifecycle.assertReservationBinding(elder, reserve, bed);
        reserveFunc.assertUnexpired(reserve);
        reserve.setReserveFlag(YesNoEnum.YES.getCode());
        reserve.setState(Reserve.REFUNDED);
        reserveMapper.updateById(reserve);
        elderMapper.cancelReserveByElderIdList(Collections.singletonList(elder.getId()));
        lifecycle.releaseBed(bed);
        return Result.success();
    }

    @Override
    public void reserveExpireJob() {
        // Only IDs are carried across the scan. Each transaction rechecks current ownership and state.
        Date today = cn.hutool.core.date.DateUtil.beginOfDay(new Date());
        List<Long> ids = reserveMapper.listExpiredActiveIds(today);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (Long id : ids) transaction.execute(status -> {
            expireOne(id);
            return null;
        });
    }

    private void expireOne(Long id) {
        Reserve locator = reserveMapper.selectById(id);
        if (locator == null) return;
        Elder elder = lifecycle.lockElder(locator.getElderId());
        Map<Long, Bed> beds = lifecycle.lockBeds(elder.getBedId(), locator.getBedId());
        Reserve reserve = reserveMapper.selectByIdForUpdate(id);
        if (reserve == null || !Reserve.ACTIVE.equals(reserve.getState())) return;
        if (reserve.getDueDate() == null || !DateUtilWen.getDayEndTime(reserve.getDueDate()).before(new Date())) return;
        Bed bed = lifecycle.requireBed(beds, reserve.getBedId());
        lifecycle.assertReservationBinding(elder, reserve, bed);
        reserve.setState(Reserve.EXPIRED);
        reserveMapper.updateById(reserve);
        elderMapper.cancelReserveByElderIdList(Collections.singletonList(elder.getId()));
        lifecycle.releaseBed(bed);
    }
}
