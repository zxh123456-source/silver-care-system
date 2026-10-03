package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.OutboundRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageOutboundRecordByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetOutboundRecordByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageOutboundRecordByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 出库登记表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface OutboundRecordMapper extends BaseMapper<OutboundRecord> {
    /**
     * 根据搜索关键字查询出库记录
     *
     * @param keyQuery
     * @param startTime
     * @param endTime
     * @return
     */
    List<PageOutboundRecordByKeyVo> listOutboundRecordByKey(@Param("keyQuery") PageOutboundRecordByKeyQuery keyQuery,
                                                            @Param("startTime") Date startTime,
                                                            @Param("endTime") Date endTime);

    /**
     * 根据编号获取出库记录信息
     *
     * @param outboundRecordId
     * @return
     */
    GetOutboundRecordByIdVo getOutboundRecordById(@Param("outboundRecordId") Long outboundRecordId);
}
