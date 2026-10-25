package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.BaseAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * 附件信息表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface BaseAttachmentMapper extends BaseMapper<BaseAttachment> {
    @Select("SELECT * FROM base_attachment WHERE id=#{id} FOR UPDATE")
    BaseAttachment lockById(@Param("id") Long id);

    @Select("SELECT * FROM base_attachment WHERE CAST(url AS BINARY)=CAST(#{url} AS BINARY) ORDER BY id LIMIT 2")
    List<BaseAttachment> findExactUrl(@Param("url") String url);

    @Update("UPDATE base_attachment SET url=#{url} WHERE id=#{id}")
    int updateCanonicalUrl(@Param("id") Long id, @Param("url") String url);

    @Update("UPDATE base_attachment SET binding_state='BOUND',update_id=#{operatorId},update_time=NOW() WHERE id=#{id} AND binding_state='DRAFT'")
    int claimDraft(@Param("id") Long id, @Param("operatorId") Long operatorId);
}
