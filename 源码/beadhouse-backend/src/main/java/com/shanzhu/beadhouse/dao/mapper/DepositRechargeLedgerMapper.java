package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.DepositRechargeLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DepositRechargeLedgerMapper {
    String COLUMNS = "id,request_id AS requestId,elder_id AS elderId,staff_id AS staffId,amount," +
            "balance_before AS balanceBefore,balance_after AS balanceAfter,create_time AS createdAt";

    @Select("SELECT " + COLUMNS + " FROM deposit_recharge_ledger WHERE request_id=#{requestId} FOR UPDATE")
    DepositRechargeLedger selectByRequestIdForUpdate(@Param("requestId") String requestId);

    @Select("SELECT " + COLUMNS + " FROM deposit_recharge_ledger WHERE request_id=#{requestId}")
    DepositRechargeLedger selectByRequestId(@Param("requestId") String requestId);

    @Insert("INSERT INTO deposit_recharge_ledger(request_id,elder_id,staff_id,amount,balance_before,balance_after) " +
            "VALUES(#{requestId},#{elderId},#{staffId},#{amount},#{balanceBefore},#{balanceAfter})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DepositRechargeLedger ledger);

    @Select("SELECT COUNT(*) FROM deposit_recharge_ledger WHERE elder_id=#{elderId}")
    long countByElderId(@Param("elderId") Long elderId);

    @Select("SELECT " + COLUMNS + " FROM deposit_recharge_ledger WHERE elder_id=#{elderId} " +
            "ORDER BY create_time DESC,id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<DepositRechargeLedger> pageByElderId(@Param("elderId") Long elderId,
            @Param("pageSize") int pageSize, @Param("offset") long offset);
}
