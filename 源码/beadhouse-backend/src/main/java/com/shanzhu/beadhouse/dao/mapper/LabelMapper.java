package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Label;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.GetElderLabelByIdLabelVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 标签表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface LabelMapper extends BaseMapper<Label> {
    /**
     * 根据老人编号获取标签
     *
     * @param elderId
     * @return
     */
    List<GetElderLabelByIdLabelVo> listElderLabelById(@Param("elderId") Long elderId);
}
