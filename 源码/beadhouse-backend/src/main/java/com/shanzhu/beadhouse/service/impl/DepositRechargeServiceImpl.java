package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.RechargeQuery;
import com.shanzhu.beadhouse.entity.vo.PageDepositRechargeByKeyVo;
import com.shanzhu.beadhouse.service.DepositRechargeService;
import com.shanzhu.beadhouse.service.common.CommonFunc;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
public class DepositRechargeServiceImpl implements DepositRechargeService {
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageDepositRechargeByKey(PageDepositRechargeByKeyQuery query) {
        // 根据搜索关键字查询预存充值信息
        List<PageDepositRechargeByKeyVo> pageDepositRechargeByKeyVoList = elderMapper.listDepositRechargeByKey(query);
        // 封装返回数据
        PageResult<PageDepositRechargeByKeyVo> pageResult = pageUtil.packPageResultData(pageDepositRechargeByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    public Result pageSearchElderByKey(PageSearchElderByKeyQuery query) {
        List<String> checkFlagList = new ArrayList<>(Arrays.asList(CheckEnum.ENTER.getStatus(), CheckEnum.EXIT_AUDIT.getStatus()));
        // 根据姓名和联系电话获取入住和退住审核老人列表
        return commonFunc.pageSearchElderByKeyResult(query,checkFlagList);
    }

    @Override
    public Result recharge(RechargeQuery query) {
        // 判断是否是入住老人
        Elder elder = elderMapper.selectById(query.getElderId());
        boolean checkFlag = Objects.equals(elder.getCheckFlag(), CheckEnum.ENTER.getStatus()) ||
                Objects.equals(elder.getCheckFlag(), CheckEnum.EXIT_AUDIT.getStatus());
        AssertUtil.isTrue(checkFlag, ExceptionEnum.NOT_ENTER);
        // 封装修改
        elder.setBalance(query.getAmount().add(elder.getBalance()));
        // 修改
        elderMapper.updateById(elder);
        return Result.success();
    }
}
