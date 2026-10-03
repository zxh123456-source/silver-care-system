package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanzhu.beadhouse.dao.mapper.WarehouseMaterialMapper;
import com.shanzhu.beadhouse.entity.po.WarehouseMaterial;
import org.springframework.stereotype.Component;

/**
 * 入库物资表公共方法
 */
@Component
public class WarehouseMaterialFunc extends ServiceImpl<WarehouseMaterialMapper, WarehouseMaterial> {
}
