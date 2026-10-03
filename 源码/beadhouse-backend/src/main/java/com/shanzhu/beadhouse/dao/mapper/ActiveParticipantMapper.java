package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.ActiveParticipant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.GetActiveByIdVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 活动参与者表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ActiveParticipantMapper extends BaseMapper<ActiveParticipant> {
    /**
     * 根据活动编号获取活动参与老人列表
     *
     * @param activeId
     * @return
     */
    List<GetActiveByIdVo.ParticipateElderVo> listParticipateElder(@Param("activeId") Long activeId);
}
