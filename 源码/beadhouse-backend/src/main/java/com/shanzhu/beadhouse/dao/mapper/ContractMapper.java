package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Contract;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.ExpireContractVo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 合同表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ContractMapper extends BaseMapper<Contract> {
    /**
     * 获取已过期和即将到期的合同
     *
     * @param endTime
     * @return
     */
    List<ExpireContractVo> listExpireContract(@Param("endTime") Date endTime);
}
