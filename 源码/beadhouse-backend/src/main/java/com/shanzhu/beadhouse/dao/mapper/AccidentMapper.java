package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Accident;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageAccidentByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetAccidentByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageAccidentByKeyVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 事故登记表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface AccidentMapper extends BaseMapper<Accident> {
    /**
     * 根据关键字获取事故登记列表
     *
     * @param keyQuery
     * @return
     */
    List<PageAccidentByKeyVo> listAccidentByKeyVo(@Param("keyQuery") PageAccidentByKeyQuery keyQuery);

    /**
     * 根据编号获取事故登记
     *
     * @param accidenId
     * @return
     */
    GetAccidentByIdVo getAccidentById(@Param("accidentId") Long accidenId);
}
