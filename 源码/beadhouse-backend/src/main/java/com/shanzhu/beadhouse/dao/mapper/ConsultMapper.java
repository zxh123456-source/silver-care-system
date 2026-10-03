package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Consult;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.GetConsultByConsultIdAndElderIdQuery;
import com.shanzhu.beadhouse.entity.query.PageConsultByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetConsultByConsultIdAndElderIdVo;
import com.shanzhu.beadhouse.entity.vo.PageConsultByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 咨询人表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface ConsultMapper extends BaseMapper<Consult> {
    /**
     * 根据搜索关键字查询咨询信息
     *
     * @param keyQuery
     * @param startTime
     * @param endTime
     * @return
     */
    List<PageConsultByKeyVo> listConsultByKey(@Param("keyQuery") PageConsultByKeyQuery keyQuery,
                                              @Param("startTime") Date startTime,
                                              @Param("endTime") Date endTime);

    /**
     * 根据咨询人编号和老人编号查询咨询信息
     *
     * @param idQuery
     * @return
     */
    List<GetConsultByConsultIdAndElderIdVo> getConsultByConsultIdAndElderId(@Param("idQuery") GetConsultByConsultIdAndElderIdQuery idQuery);
}
