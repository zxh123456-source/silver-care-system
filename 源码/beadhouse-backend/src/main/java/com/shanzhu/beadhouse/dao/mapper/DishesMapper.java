package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Dishes;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageDishesByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageDishesByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 菜品表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface DishesMapper extends BaseMapper<Dishes> {
    /**
     * 根据搜索关键字查询菜品
     *
     * @param keyQuery
     * @return
     */
    List<PageDishesByKeyVo> listDishesByKey(@Param("keyQuery") PageDishesByKeyQuery keyQuery);

    /**
     * 根据老人编号获取该老人所选套餐的菜品列表
     *
     * @param elderId
     * @return
     */
    List<Dishes> listSetDishesByElderId(@Param("elderId") Long elderId);
}
