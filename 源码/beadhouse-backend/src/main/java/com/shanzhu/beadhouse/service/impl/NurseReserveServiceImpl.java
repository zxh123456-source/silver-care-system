package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjUtil;
import com.shanzhu.beadhouse.common.constant.ChargeEnum;
import com.shanzhu.beadhouse.common.constant.ConsumeEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.NurseReserveMapper;
import com.shanzhu.beadhouse.entity.po.NurseReserve;
import com.shanzhu.beadhouse.entity.po.ServiceItem;
import com.shanzhu.beadhouse.entity.query.AddNurseReserveQuery;
import com.shanzhu.beadhouse.entity.query.ExecuteNurseReserveQuery;
import com.shanzhu.beadhouse.entity.query.PageNurseReserveByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageNurseReserveByKeyVo;
import com.shanzhu.beadhouse.service.NurseReserveService;
import com.shanzhu.beadhouse.service.common.ConsumeFunc;
import com.shanzhu.beadhouse.service.common.ElderFunc;
import com.shanzhu.beadhouse.service.common.ServiceItemFunc;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

@Service
public class NurseReserveServiceImpl implements NurseReserveService {
    @Resource
    private ServiceItemFunc serviceItemFunc;
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private NurseReserveMapper nurseReserveMapper;
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private ConsumeFunc consumeFunc;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageNurseReserveByKey(PageNurseReserveByKeyQuery query) {
        // 根据关键词获取护理预定列表
        List<PageNurseReserveByKeyVo> pageNurseReserveByKeyVoList = nurseReserveMapper.listNurseReserveByKey(query);
        // 封装返回数据
        PageResult<PageNurseReserveByKeyVo> pageResult = pageUtil.packPageResultData(pageNurseReserveByKeyVoList, query.getPageNum(), query.getPageSize());
        // 替换订单状态
        pageResult.getList().forEach(pageNurseReserveByKeyVo -> pageNurseReserveByKeyVo.setOrderFlag(
                Objects.equals(pageNurseReserveByKeyVo.getOrderFlag(), YesNoEnum.NO.getCode()) ?
                        "待支付" :
                        "已完成"
        ));
        return Result.success(pageResult);
    }

    @Override
    public Result listService() {
        // 根据搜索关键字查询服务项目
        List<ServiceItem> listNotDelServiceItem = serviceItemFunc.listNotDelServiceItemByKey(null, null, ChargeEnum.ONCE.getMethod());
        return Result.success(BeanUtil.copyToList(listNotDelServiceItem, DropDown.class));
    }

    @Override
    public Result addNurseReserve(AddNurseReserveQuery query) {
        // 初始化护理预定
        NurseReserve nurseReserve = BeanUtil.toBean(query, NurseReserve.class);
        nurseReserve.setOrderFlag(YesNoEnum.NO.getCode());
        // 新增
        nurseReserveMapper.insert(nurseReserve);
        return Result.success();
    }

    @Override
    public Result listNurseStaff() {
        return Result.success(BeanUtil.copyToList(staffFunc.listStaffByRoleId(5L), DropDown.class));
    }

    @Override
    @Transactional
    public Result executeNurseReserve(ExecuteNurseReserveQuery query) {
        // 根据编号获取护理预定
        NurseReserve getNurseReserveById = nurseReserveMapper.selectById(query.getId());
        // 判断订单是否已完成
        boolean checkOrderFlag = ObjUtil.isNotEmpty(getNurseReserveById.getStaffId()) ||
                ObjUtil.isNotEmpty(getNurseReserveById.getNurseDate()) ||
                Objects.equals(getNurseReserveById.getOrderFlag(), YesNoEnum.YES.getCode());
        AssertUtil.notTrue(checkOrderFlag, ExceptionEnum.ORDER_SUCCESS);
        // 封装护理预定修改
        NurseReserve nurseReserve = BeanUtil.toBean(query, NurseReserve.class);
        nurseReserve.setOrderFlag(YesNoEnum.YES.getCode());
        // 修改护理预定
        nurseReserveMapper.updateById(nurseReserve);
        // 老人扣费
        elderFunc.deductionFee(getNurseReserveById.getElderId(), getNurseReserveById.getPayAmount());
        // 新增消费记录
        consumeFunc.addConsume(getNurseReserveById.getElderId(), ConsumeEnum.NURSE.getType(), getNurseReserveById.getPayAmount(), nurseReserve.getNurseDate());
        return Result.success();
    }
}
