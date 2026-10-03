package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.EmergencyContact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.ExpireContractEmergencyContactVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 紧急联系人表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface EmergencyContactMapper extends BaseMapper<EmergencyContact> {
    /**
     * 根据老人编号列表获取紧急联系人列表
     *
     * @param elderIdList
     * @return
     */
    List<ExpireContractEmergencyContactVo> listExpireContractEmergencyContactVoByElderIdList(@Param("elderIdList") List<Long> elderIdList);
}
