package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Material;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageMaterialByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageMaterialByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 物资表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface MaterialMapper extends BaseMapper<Material> {
    /**
     * 根据搜索关键字获取物资列表
     *
     * @param keyQuery
     * @return
     */
    List<PageMaterialByKeyVo> listMaterialByKey(@Param("keyQuery") PageMaterialByKeyQuery keyQuery);
}
