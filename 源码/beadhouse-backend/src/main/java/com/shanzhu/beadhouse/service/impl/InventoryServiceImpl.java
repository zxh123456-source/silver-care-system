package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.WarehouseMaterialMapper;
import com.shanzhu.beadhouse.entity.query.PageInventoryByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageInventoryByKeyVo;
import com.shanzhu.beadhouse.service.InventoryService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryServiceImpl implements InventoryService {
    @Resource
    private WarehouseMaterialMapper warehouseMaterialMapper;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result pageInventoryByKey(PageInventoryByKeyQuery query) {
        // 根据关键字获取库存列表
        List<PageInventoryByKeyVo> pageIntentionByKeyVoList = warehouseMaterialMapper.listInventoryByKey(query);
        // 将库存列表根据物资编号分组
        Map<Long, List<PageInventoryByKeyVo>> materialInventoryMap = pageIntentionByKeyVoList.parallelStream()
                .collect(Collectors.groupingBy(PageInventoryByKeyVo::getMaterialId));
        // 设置总库存
        pageIntentionByKeyVoList.forEach(pageInventoryByKeyVo -> {
            Integer[] total = {0};
            materialInventoryMap.get(pageInventoryByKeyVo.getMaterialId()).forEach(inventory ->
                    total[0] += inventory.getInventory()
            );
            pageInventoryByKeyVo.setTotal(total[0]);
        });
        // 封装返回数据
        PageResult<PageInventoryByKeyVo> pageResult = pageUtil.packPageResultData(pageIntentionByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }
}
