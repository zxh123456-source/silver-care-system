package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Elder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageDepositRechargeByKeyVo;
import com.shanzhu.beadhouse.entity.vo.PageElderByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 老人表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ElderMapper extends BaseMapper<Elder> {
    /**
     * 根据搜索关键字查询预存充值信息
     *
     * @param keyQuery
     * @return
     */
    List<PageDepositRechargeByKeyVo> listDepositRechargeByKey(@Param("keyQuery") PageDepositRechargeByKeyQuery keyQuery);

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
    List<PageElderByKeyVo> listElderByKey(@Param("keyQuery") PageElderByKeyQuery keyQuery);

    /**
     * 根据编号将老人设置退住
     *
     * @param elderId
     */
    void retreatElderById(@Param("elderId") Long elderId);
}
