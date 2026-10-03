package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Visit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageVisitByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetVisitByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageVisitByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 来访登记表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface VisitMapper extends BaseMapper<Visit> {
    /**
     * 根据关键字获取来访登记
     *
     * @param keyQuery
     * @return
     */
    List<PageVisitByKeyVo> listVisitByKey(@Param("keyQuery") PageVisitByKeyQuery keyQuery);

    /**
     * 根据编号获取来访登记
     *
     * @param visitId
     * @return
     */
    GetVisitByIdVo getVisitById(@Param("visitId") Long visitId);
}
