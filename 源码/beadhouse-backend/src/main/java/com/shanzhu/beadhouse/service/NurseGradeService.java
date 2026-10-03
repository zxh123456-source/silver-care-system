package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.OperateNurseGradeQuery;
import com.shanzhu.beadhouse.entity.query.PageNurseGradeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageServiceByKeyQuery;

public interface NurseGradeService {
    /**
     * 分页查询护理等级
     *
     * @param pageNurseGradeByKeyQuery
     * @return
     */
    Result pageNurseGradeByKey(PageNurseGradeByKeyQuery pageNurseGradeByKeyQuery);

    /**
     * 分页查询服务
     *
     * @param pageServiceByKeyQuery
     * @return
     */
    Result pageServiceByKey(PageServiceByKeyQuery pageServiceByKeyQuery);

    /**
     * 新增护理等级
     *
     * @param operateNurseGradeQuery
     * @return
     */
    Result addNurseGrade(OperateNurseGradeQuery operateNurseGradeQuery);

    /**
     * 根据编号查询护理等级
     *
     * @param nurseGradeId
     * @return
     */
    Result getNurseGradeById(Long nurseGradeId);

    /**
     * 编辑护理等级
     *
     * @param operateNurseGradeQuery
     * @return
     */
    Result editNurseGrade(OperateNurseGradeQuery operateNurseGradeQuery);

    /**
     * 删除护理等级
     *
     * @param nurseGradeId
     * @return
     */
    Result deleteNurseGrade(Long nurseGradeId);
}
