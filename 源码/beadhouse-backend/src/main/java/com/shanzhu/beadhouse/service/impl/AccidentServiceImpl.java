package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.DateUtilWen;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.AccidentMapper;
import com.shanzhu.beadhouse.entity.po.Accident;
import com.shanzhu.beadhouse.entity.query.AddAccidentQuery;
import com.shanzhu.beadhouse.entity.query.EditAccidentQuery;
import com.shanzhu.beadhouse.entity.query.PageAccidentByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.GetAccidentByIdVo;
import com.shanzhu.beadhouse.entity.vo.PageAccidentByKeyVo;
import com.shanzhu.beadhouse.service.AccidentService;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;

@Service
public class AccidentServiceImpl implements AccidentService {
    @Resource
    private com.shanzhu.beadhouse.service.common.AiDataScopeService dataScopeService;
    @Resource
    private AccidentMapper accidentMapper;
    @Resource
    private AttachmentAccessService attachmentAccessService;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageAccidentByKey(PageAccidentByKeyQuery query) {
        // 根据关键字获取事故登记列表
        List<PageAccidentByKeyVo> pageAccidentByKeyVoList = accidentMapper.listAccidentByKeyVo(query, dataScopeService.queryStaffId());
        // 封装返回数据
        PageResult<PageAccidentByKeyVo> pageResult = pageUtil.packPageResultData(pageAccidentByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result addAccident(AddAccidentQuery query) {
        dataScopeService.assertElderAccess(query.getElderId());
        // 初始化事故登记
        Accident accident = new Accident();
        accident.setElderId(query.getElderId());
        accident.setStaffId(query.getStaffId());
        accident.setDescription(query.getDescription());
        accident.setOccurDate(DateUtilWen.dateStrToDate(query.getOccurDate()));
        accident.setDelFlag(YesNoEnum.NO.getCode());
        accident.setPicture("");
        // 新增
        accidentMapper.insert(accident);
        Accident image = new Accident();
        image.setId(accident.getId());
        image.setPicture(storedImage(attachmentAccessService.bind("ACCIDENT", accident.getId(), "", query.getPicture())));
        accidentMapper.updateById(image);
        return Result.success();
    }

    @Override
    public Result getAccidentById(Long accidentId) {
        Accident record = accidentMapper.selectById(accidentId);
        AssertUtil.notNull(record, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(record.getElderId());
        AssertUtil.notTrue(Objects.equals(record.getDelFlag(), YesNoEnum.YES.getCode()), ExceptionEnum.RECORD_ALREADY_DELETE);
        // 根据编号获取事故登记
        GetAccidentByIdVo accidentById = accidentMapper.getAccidentById(accidentId);
        // 验证事故登记是否存在
        AssertUtil.notNull(accidentById, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(accidentById);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result editAccident(EditAccidentQuery query) {
        Accident stored = requireAccidentForUpdate(query.getId(), false);
        String requestedImage = query.getPicture() == null ? stored.getPicture() : query.getPicture();
        String picture = attachmentAccessService.bind("ACCIDENT", stored.getId(), stored.getPicture(), requestedImage);
        // Only editable business fields are copied; the stored elder remains the photo's owner.
        Accident update = new Accident();
        update.setId(stored.getId());
        update.setStaffId(query.getStaffId());
        update.setDescription(query.getDescription());
        update.setOccurDate(DateUtilWen.dateStrToDate(query.getOccurDate()));
        update.setPicture(storedImage(picture));
        accidentMapper.updateById(update);
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result deleteAccident(Long accidentId) {
        Accident stored = requireAccidentForUpdate(accidentId, true);
        Accident update = new Accident();
        update.setId(stored.getId());
        update.setDelFlag(YesNoEnum.YES.getCode());
        accidentMapper.updateById(update);
        attachmentAccessService.retire("ACCIDENT", stored.getId());
        return Result.success();
    }

    private Accident requireAccidentForUpdate(Long id, boolean deletion) {
        AssertUtil.notNull(id, ExceptionEnum.DATA_NOT_EXIST);
        Accident stored = accidentMapper.selectByIdForUpdate(id);
        AssertUtil.notNull(stored, ExceptionEnum.DATA_NOT_EXIST);
        dataScopeService.assertElderAccess(stored.getElderId());
        AssertUtil.notTrue(Objects.equals(stored.getDelFlag(), YesNoEnum.YES.getCode()),
                deletion ? ExceptionEnum.DEL_REPEAT : ExceptionEnum.RECORD_ALREADY_DELETE);
        return stored;
    }

    private String storedImage(String url) {
        return url == null ? "" : url;
    }
}
