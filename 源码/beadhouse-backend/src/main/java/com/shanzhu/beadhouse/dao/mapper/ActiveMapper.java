package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Active;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageActiveByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageActiveByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 活动表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-101
 */
public interface ActiveMapper extends BaseMapper<Active> {
    /**
     * 根据关键词查询活动
     *
     * @param keyQuery
     * @param startTime
     * @param endTime
     * @return
     */
    List<PageActiveByKeyVo> listActiveByKey(@Param("keyQuery") PageActiveByKeyQuery keyQuery,
                                            @Param("startTime") Date startTime,
                                            @Param("endTime") Date endTime);
}
