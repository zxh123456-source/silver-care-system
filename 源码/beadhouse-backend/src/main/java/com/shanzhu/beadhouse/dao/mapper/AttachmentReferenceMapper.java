package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.AttachmentReference;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AttachmentReferenceMapper extends BaseMapper<AttachmentReference> {
    @Select("SELECT * FROM attachment_reference WHERE attachment_id=#{attachmentId} ORDER BY id FOR UPDATE")
    List<AttachmentReference> lockByAttachment(@Param("attachmentId") Long attachmentId);

    @Update("UPDATE attachment_reference SET active=#{active},update_id=#{operatorId},update_time=NOW() WHERE id=#{id}")
    int setActive(@Param("id") Long id, @Param("active") String active, @Param("operatorId") Long operatorId);
}
