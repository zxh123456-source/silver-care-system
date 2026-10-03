package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Consume;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.PageConsumeByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 消费记录表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ConsumeMapper extends BaseMapper<Consume> {
    /**
     * 根据搜索关键字获取消费记录
     *
     * @param elderName
     * @param startTime
     * @param endTime
     * @return
     */
    List<PageConsumeByKeyVo> listConsumeByKey(@Param("elderName") String elderName,
                                              @Param("startTime") Date startTime,
                                              @Param("endTime") Date endTime);
}
