package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.RetreatApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageRetreatApplyQuery;
import com.shanzhu.beadhouse.entity.query.PageRetreatAuditQuery;
import com.shanzhu.beadhouse.entity.vo.PageRetreatByKeyVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
    @Select("SELECT * FROM retreat_apply WHERE id=#{id} FOR UPDATE")
    RetreatApply selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM retreat_apply WHERE pending_elder_id=#{elderId} FOR UPDATE")
    RetreatApply selectPendingForUpdate(@Param("elderId") Long elderId);
    /**
     * 根据搜索关键字查询退住申请信息
     *
     * @param keyQuery
     * @return
     */
    List<PageRetreatByKeyVo> listRetreatApplyByKey(@Param("keyQuery") PageRetreatApplyQuery keyQuery,
            @Param("scopeStaffId") Long scopeStaffId);

    /**
     * 根据搜索关键字查询退住申请信息
     *
     * @param keyQuery
     * @return
     */
    List<PageRetreatByKeyVo> listRetreatAuditByKey(@Param("keyQuery") PageRetreatAuditQuery keyQuery,
            @Param("scopeStaffId") Long scopeStaffId);
}
