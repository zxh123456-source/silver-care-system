package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.SetDishes;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.vo.GetCateringSetByIdVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 套餐食物表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface SetDishesMapper extends BaseMapper<SetDishes> {
    /**
     * 根据套餐编号获取食物信息并设值
     *
     * @param setId
     * @return
     */
    List<GetCateringSetByIdVo.SetDishesVo> listSetDishesBySetId(@Param("setId") Long setId);
}
