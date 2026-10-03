package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.NurseReserve;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageNurseReserveByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageNurseReserveByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 护理预定表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface NurseReserveMapper extends BaseMapper<NurseReserve> {
    /**
     * 根据关键词获取护理预定列表
     *
     * @param keyQuery
     * @return
     */
    List<PageNurseReserveByKeyVo> listNurseReserveByKey(@Param("keyQuery") PageNurseReserveByKeyQuery keyQuery);
}
