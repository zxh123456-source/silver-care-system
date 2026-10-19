package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Elder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageDepositRechargeByKeyVo;
import com.shanzhu.beadhouse.entity.vo.PageElderByKeyVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.math.BigDecimal;

import java.util.List;
import java.util.Collection;

/**
 * <p>
 * 老人表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ElderMapper extends BaseMapper<Elder> {
    @Select("SELECT * FROM elder WHERE id=#{id} FOR UPDATE")
    Elder selectByIdForUpdate(@Param("id") Long id);

    List<Long> listAccessibleElderIds(@Param("scopeStaffId") Long scopeStaffId, @Param("candidateIds") Collection<Long> candidateIds);
    @Update("UPDATE elder SET balance=balance+#{amount},update_id=#{staffId},update_time=NOW() " +
            "WHERE id=#{elderId} AND check_flag IN ('入住','退住审核') AND balance>=0 AND balance<=#{maximum}-#{amount}")
    int creditBalance(@Param("elderId") Long elderId, @Param("amount") BigDecimal amount,
                      @Param("maximum") BigDecimal maximum, @Param("staffId") Long staffId);

    @Update("UPDATE elder SET balance=balance-#{amount},update_id=#{staffId},update_time=NOW() " +
            "WHERE id=#{elderId} AND balance>=#{amount}")
    int debitBalance(@Param("elderId") Long elderId, @Param("amount") BigDecimal amount, @Param("staffId") Long staffId);

    /**
     * 根据搜索关键字查询预存充值信息
     *
     * @param keyQuery
     * @return
     */
    List<PageDepositRechargeByKeyVo> listDepositRechargeByKey(@Param("keyQuery") PageDepositRechargeByKeyQuery keyQuery,
            @Param("scopeStaffId") Long scopeStaffId);

    List<Elder> listScopedElders(@Param("name") String name, @Param("phone") String phone,
            @Param("checkFlags") List<String> checkFlags, @Param("scopeStaffId") Long scopeStaffId);

    /**
     * 根据老人编号列表批量取消预定
     *
     * @param elderIdList
     */
    void cancelReserveByElderIdList(@Param("elderIdList") List<Long> elderIdList);

    /**
     * 根据编号逻辑删除老人
     *
     * @param elderId
     */
    void deleteElderById(@Param("elderId") Long elderId);

    /**
     * 根据关键字获取长者列表
     *
     * @param keyQuery
     * @return
     */
    List<PageElderByKeyVo> listElderByKey(@Param("keyQuery") PageElderByKeyQuery keyQuery,
            @Param("scopeStaffId") Long scopeStaffId);

    /**
     * 根据编号将老人设置退住
     *
     * @param elderId
     */
    void retreatElderById(@Param("elderId") Long elderId);
}
