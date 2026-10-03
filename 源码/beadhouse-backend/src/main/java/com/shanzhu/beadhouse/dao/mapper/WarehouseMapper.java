package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Warehouse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageWarehouseByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageWarehouseByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 仓库表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface WarehouseMapper extends BaseMapper<Warehouse> {
    /**
     * 根据关键字获取仓库列表
     *
     * @param keyQuery
     * @return
     */
    List<PageWarehouseByKeyVo> listWarehouseByKey(@Param("keyQuery") PageWarehouseByKeyQuery keyQuery);
}
