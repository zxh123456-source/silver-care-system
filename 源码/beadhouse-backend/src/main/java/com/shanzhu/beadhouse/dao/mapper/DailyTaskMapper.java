package com.shanzhu.beadhouse.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.DailyTask;
import com.shanzhu.beadhouse.entity.base.DropDown;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface DailyTaskMapper extends BaseMapper<DailyTask> {
    int upsertSnapshot(@Param("task") DailyTask task);
    DailyTask lockById(@Param("id") Long id);
    List<DropDown> eligibleOwners(@Param("elderId") Long elderId, @Param("adminRoleId") long adminRoleId);
}
