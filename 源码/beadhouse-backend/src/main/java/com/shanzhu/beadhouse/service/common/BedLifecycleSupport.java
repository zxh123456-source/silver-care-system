package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.BedEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.dao.mapper.BedMapper;
import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.po.Bed;
import com.shanzhu.beadhouse.entity.po.Elder;
import com.shanzhu.beadhouse.entity.po.Reserve;
import com.shanzhu.beadhouse.common.constant.CheckEnum;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.*;

/** All callers hold a transaction: elder first, beds in ascending ID order, then source records. */
@Component
public class BedLifecycleSupport {
    @Resource private ElderMapper elderMapper;
    @Resource private BedMapper bedMapper;

    public Elder lockElder(Long id) {
        AssertUtil.notNull(id, ExceptionEnum.PARAM_ERROR);
        Elder elder = elderMapper.selectByIdForUpdate(id);
        AssertUtil.notNull(elder, ExceptionEnum.DATA_NOT_EXIST);
        return elder;
    }

    public Map<Long, Bed> lockBeds(Long... ids) {
        SortedSet<Long> ordered = new TreeSet<>();
        for (Long id : ids) if (id != null) ordered.add(id);
        Map<Long, Bed> beds = new LinkedHashMap<>();
        for (Long id : ordered) beds.put(id, bedMapper.selectByIdForUpdate(id));
        return beds;
    }

    public Bed requireBed(Map<Long, Bed> beds, Long id) {
        Bed bed = beds.get(id);
        if (bed == null || !"N".equals(bed.getDelFlag()))
            throw new BusinessRuntimeException(409, "床位不存在或已删除，请刷新后重试");
        return bed;
    }

    public void assertState(Elder elder, String... allowed) {
        if (!Arrays.asList(allowed).contains(elder.getCheckFlag()))
            throw new BusinessRuntimeException(409, "老人当前状态不允许此操作，请刷新后重试");
    }

    public void assertBedState(Bed bed, String expected) {
        if (bed == null || !"N".equals(bed.getDelFlag()) || !Objects.equals(expected, bed.getBedFlag()))
            throw new BusinessRuntimeException(409, "床位状态已变化，请刷新后重试");
    }

    public void assertReservationBinding(Elder elder, Reserve reserve, Bed bed) {
        assertState(elder, CheckEnum.RESERVE.getStatus());
        assertBedState(bed, BedEnum.RESERVE.getStatus());
        if (reserve == null || !Reserve.ACTIVE.equals(reserve.getState()) || !"N".equals(reserve.getReserveFlag())
                || !Objects.equals(reserve.getElderId(), elder.getId())
                || !Objects.equals(reserve.getBedId(), elder.getBedId())
                || !Objects.equals(reserve.getBedId(), bed.getId()))
            throw new BusinessRuntimeException(409, "预订与当前床位不一致，请核对后重试");
    }

    public void setBedState(Bed lockedBed, String state) {
        Bed change = new Bed();
        change.setId(lockedBed.getId());
        change.setBedFlag(state);
        if (bedMapper.updateById(change) != 1)
            throw new BusinessRuntimeException(409, "床位状态更新失败，请刷新后重试");
        lockedBed.setBedFlag(state);
    }

    public void releaseBed(Bed lockedBed) {
        setBedState(lockedBed, BedEnum.IDLE.getStatus());
    }
}
