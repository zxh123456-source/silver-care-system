package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.Staff;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.query.PageStaffByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageStaffByKeyVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 员工表 Mapper 接口
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
public interface StaffMapper extends BaseMapper<Staff> {
    @Select("SELECT * FROM staff WHERE id = #{id} FOR UPDATE")
    Staff selectByIdForUpdate(@Param("id") Long id);

    /**
     * 根据关键字查询员工列表
     *
     * @param keyQuery
     * @return
     */
    List<PageStaffByKeyVo> listStaffByKey(@Param("keyQuery") PageStaffByKeyQuery keyQuery);
}
