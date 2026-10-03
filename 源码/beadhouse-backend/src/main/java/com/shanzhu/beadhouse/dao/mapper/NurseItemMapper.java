package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.NurseItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.GetNurseGradeByIdVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 护理项目表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface NurseItemMapper extends BaseMapper<NurseItem> {
    /**
     * 根据护理等级编号获取服务列表
     *
     * @param gradeId
     * @return
     */
    List<GetNurseGradeByIdVo.NurseGradeServiceVo> listGradeService(@Param("gradeId") Long gradeId);
}
