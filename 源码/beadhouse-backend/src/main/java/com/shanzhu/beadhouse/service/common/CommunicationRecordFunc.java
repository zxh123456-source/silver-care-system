package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.dao.mapper.CommunicationRecordMapper;
import com.shanzhu.beadhouse.entity.po.CommunicationRecord;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 沟通记录表公共方法
 */
@Component
public class CommunicationRecordFunc {
    @Resource
    private CommunicationRecordMapper communicationRecordMapper;

    /**
     * 获取所有未被删除的沟通记录
     *
     * @return
     */
    public List<CommunicationRecord> listNotDelCommunicationRecord(Long elderId) {
        return communicationRecordMapper.selectList(new LambdaQueryWrapper<CommunicationRecord>()
                .eq(CommunicationRecord::getElderId, elderId)
                .eq(CommunicationRecord::getDelFlag, YesNoEnum.NO.getCode()));
    }
}
