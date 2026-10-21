package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.constant.CheckEnum;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.service.common.BillingAmount;
import java.math.BigDecimal;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.DepositRechargeLedgerMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.po.DepositRechargeLedger;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.RechargeQuery;
import com.shanzhu.beadhouse.entity.vo.PageDepositRechargeByKeyVo;
import com.shanzhu.beadhouse.entity.vo.RechargeReceiptVo;
import com.shanzhu.beadhouse.service.DepositRechargeService;
import com.shanzhu.beadhouse.service.common.CommonFunc;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DepositRechargeServiceImpl implements DepositRechargeService {
    private static final Pattern REQUEST_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    @Resource
    private com.shanzhu.beadhouse.service.common.AiDataScopeService dataScopeService;
    @Resource
    private CommonFunc commonFunc;
    @Resource
    private ElderMapper elderMapper;
    @Resource
    private DepositRechargeLedgerMapper rechargeLedgerMapper;
    @Resource
    private PageUtil pageUtil;
    @Resource
    private AuthorityAssert authorityAssert;

    @Override
    public Result pageDepositRechargeByKey(PageDepositRechargeByKeyQuery query) {
        // 根据搜索关键字查询预存充值信息
        List<PageDepositRechargeByKeyVo> pageDepositRechargeByKeyVoList = elderMapper.listDepositRechargeByKey(query, dataScopeService.queryStaffId());
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result recharge(RechargeQuery query) {
        if (query == null) throw new BusinessRuntimeException(400, "充值请求不能为空");
        dataScopeService.assertElderAccess(query.getElderId());
        String requestId = requireRequestId(query.getRequestId());
        BigDecimal amount = BillingAmount.require(query.getAmount(), false);
        Long staffId = authorityAssert.getLoginUserId();
        // Always take the elder lock before the request key. READ_COMMITTED ensures a
        // request waiting here observes the receipt committed by an earlier request.
        Elder elder = elderMapper.selectByIdForUpdate(query.getElderId());
        AssertUtil.notNull(elder, ExceptionEnum.DATA_NOT_EXIST);
        DepositRechargeLedger previous = rechargeLedgerMapper.selectByRequestIdForUpdate(requestId);
        if (previous != null) {
            if (!Objects.equals(previous.getElderId(), query.getElderId()) ||
                    !Objects.equals(previous.getStaffId(), staffId) || previous.getAmount().compareTo(amount) != 0) {
                throw requestConflict();
            }
            return Result.success(RechargeReceiptVo.from(previous));
        }
        // Replaying a successful receipt does not perform a new charge, so only a
        // previously unseen request must still satisfy the admission-state rules.
        boolean checkFlag = Objects.equals(elder.getCheckFlag(), CheckEnum.ENTER.getStatus()) ||
                Objects.equals(elder.getCheckFlag(), CheckEnum.EXIT_AUDIT.getStatus());
        AssertUtil.isTrue(checkFlag, ExceptionEnum.NOT_ENTER);
        if (elderMapper.creditBalance(query.getElderId(), amount, BillingAmount.MAXIMUM, staffId) != 1) {
            throw new BusinessRuntimeException(409, "老人状态或余额已变化，请刷新后重试；余额不能超过99999999.99元");
        }
        DepositRechargeLedger ledger = new DepositRechargeLedger();
        ledger.setRequestId(requestId);
        ledger.setElderId(elder.getId());
        ledger.setStaffId(staffId);
        ledger.setAmount(amount);
        ledger.setBalanceBefore(elder.getBalance());
        ledger.setBalanceAfter(elder.getBalance().add(amount));
        try {
            if (rechargeLedgerMapper.insert(ledger) != 1) {
                throw new BusinessRuntimeException(500, "充值凭证保存失败");
            }
        } catch (DuplicateKeyException conflict) {
            // Different elders have different row locks. The unique request key
            // resolves that race, and throwing rolls this elder's credit back.
            throw requestConflict();
        }
        // Read the database-generated timestamp so the first response and every
        // replay expose exactly the same persisted receipt.
        return Result.success(RechargeReceiptVo.from(rechargeLedgerMapper.selectByRequestId(requestId)));
    }

    @Override
    public Result getRechargeReceipt(Long elderId, String requestId) {
        dataScopeService.assertElderAccess(elderId);
        DepositRechargeLedger ledger = rechargeLedgerMapper.selectByRequestId(requireRequestId(requestId));
        AssertUtil.notNull(ledger, ExceptionEnum.DATA_NOT_EXIST);
        if (!Objects.equals(ledger.getElderId(), elderId) ||
                (!dataScopeService.isAdmin() && !Objects.equals(ledger.getStaffId(), authorityAssert.getLoginUserId()))) {
            throw new BusinessRuntimeException(403, "无权访问该充值凭证");
        }
        return Result.success(RechargeReceiptVo.from(ledger));
    }

    @Override
    @Transactional(readOnly = true)
    public Result pageRechargeLedger(Long elderId, Integer pageNum, Integer pageSize) {
        dataScopeService.assertElderAccess(elderId);
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1) {
            throw new BusinessRuntimeException(400, "页码和每页数量必须为正整数");
        }
        int limit = Math.min(pageSize, 100);
        long offset = (pageNum.longValue() - 1L) * limit;
        long total = rechargeLedgerMapper.countByElderId(elderId);
        List<RechargeReceiptVo> receipts = rechargeLedgerMapper.pageByElderId(elderId, limit, offset).stream()
                .map(RechargeReceiptVo::from).collect(Collectors.toList());
        return Result.success(new PageResult<>(pageNum, limit, total, receipts));
    }

    private String requireRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new BusinessRuntimeException(400, "充值单号必须为完整UUID，原单重试请使用原单号");
        }
        return requestId.toLowerCase(Locale.ROOT);
    }

    private BusinessRuntimeException requestConflict() {
        return new BusinessRuntimeException(409, "充值单号已用于另一笔充值，请核对原单号、老人和金额");
    }
}
