package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.RetreatApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageRetreatApplyQuery;
import com.shanzhu.beadhouse.entity.query.PageRetreatAuditQuery;
import com.shanzhu.beadhouse.entity.vo.PageRetreatByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 退住申请表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface RetreatApplyMapper extends BaseMapper<RetreatApply> {
    /**
     * 根据搜索关键字查询退住申请信息
     *
     * @param keyQuery
     * @return
     */
    List<PageRetreatByKeyVo> listRetreatApplyByKey(@Param("keyQuery") PageRetreatApplyQuery keyQuery);

    /**
     * 根据搜索关键字查询退住申请信息
     *
     * @param keyQuery
     * @return
     */
    List<PageRetreatByKeyVo> listRetreatAuditByKey(@Param("keyQuery") PageRetreatAuditQuery keyQuery);
}
