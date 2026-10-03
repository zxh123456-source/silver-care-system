package com.shanzhu.beadhouse.service.common;

import cn.hutool.core.bean.BeanUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.DropDown;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchStaffByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageSearchElderByKeyVo;
import com.shanzhu.beadhouse.entity.vo.PageSearchStaffByKeyVo;
import com.shanzhu.beadhouse.entity.vo.TreeVo;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公共方法
 */
@Component
public class CommonFunc {
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private StaffFunc staffFunc;
    @Resource
    private BuildingFunc buildingFunc;
    @Resource
    private FloorFunc floorFunc;
    @Resource
    private RoomFunc roomFunc;
    @Resource
    private BedFunc bedFunc;
    @Resource
    private PageUtil pageUtil;

    /**
     * 封装搜索老人列表
     *
     * @param query
     * @param checkFlagList
     * @return
     */
    public List<PageSearchElderByKeyVo> listPageElderByKey(PageSearchElderByKeyQuery query, List<String> checkFlagList) {
        // 根据姓名和联系电话获取不同入住状态的老人列表
        List<Elder> listElderByKey = elderFunc.listElderByKey(query.getName(), query.getPhone(), checkFlagList);
        // 实体转换
        return BeanUtil.copyToList(listElderByKey, PageSearchElderByKeyVo.class);

    }

    /**
     * 封装搜索老人列表返回数据
     *
     * @param query
     * @param checkFlagList
     * @return
     */
    public Result pageSearchElderByKeyResult(PageSearchElderByKeyQuery query, List<String> checkFlagList) {
        // 实体转换
        List<PageSearchElderByKeyVo> pageSearchElderByKeyVoList = listPageElderByKey(query, checkFlagList);
        // 封装返回数据
        PageResult<PageSearchElderByKeyVo> pageResult = pageUtil.packPageResultData(pageSearchElderByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    /**
     * 封装搜索员工列表返回数据
     *
     * @param query
     * @param roleId
     * @return
     */
    public Result pageSearchStaffByKeyResult(PageSearchStaffByKeyQuery query, Long roleId) {
        // 根据关键字获取员工列表
        List<Staff> staffList = staffFunc.listStaffByKey(roleId, query.getName(), query.getSex());
        // 转换实体
        List<PageSearchStaffByKeyVo> pageSearchStaffByKeyVoList = BeanUtil.copyToList(staffList, PageSearchStaffByKeyVo.class);
        // 封装返回数据
        PageResult<PageSearchStaffByKeyVo> pageResult = pageUtil.packPageResultData(pageSearchStaffByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    /**
     * 封装楼栋树返回数据
     *
     * @param idleFlag
     * @return
     */
    public Result getBuildingTreeResult(Boolean idleFlag) {
        // 获取所有未被删除的楼栋
        List<Building> listNotDelBuilding = buildingFunc.listNotDelBuilding();
        // 获取所有未被删除的楼层
        List<Floor> listNotDelFloor = floorFunc.listNotDelFloor();
        // 获取所有未被删除的房间
        List<Room> listNotDelRoom = roomFunc.listNotDelRoom();
        // 获取所有未被删除床位
        List<Bed> listIdleBed = idleFlag ? bedFunc.listIdleBed() : bedFunc.listNotDelBed();
        // 生成楼栋树
        List<TreeVo> buildingTree = buildingFunc.generateBuildingSimpleTree(listNotDelBuilding, listNotDelFloor, listNotDelRoom, listIdleBed, true);
        return Result.success(buildingTree);
    }

    /**
     * 根据编号分组DropDown
     *
     * @param list
     * @return
     */
    public Map<Long, List<DropDown>> mapDropDownById(List<DropDown> list) {
        return list.parallelStream().collect(Collectors.groupingBy(DropDown::getId));
    }
}
