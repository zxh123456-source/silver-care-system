package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.BedEnum;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.constant.MarkEnum;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import com.shanzhu.beadhouse.common.util.PageUtil;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.BedMapper;
import com.shanzhu.beadhouse.dao.mapper.BuildingMapper;
import com.shanzhu.beadhouse.dao.mapper.FloorMapper;
import com.shanzhu.beadhouse.dao.mapper.RoomMapper;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.*;
import com.shanzhu.beadhouse.entity.vo.*;
import com.shanzhu.beadhouse.service.BuildService;
import com.shanzhu.beadhouse.service.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.stream.Collectors;

@Service
public class BuildServiceImpl implements BuildService {
    @Resource private BedLifecycleSupport bedLifecycle;
    @Resource
    private BuildingMapper buildingMapper;
    @Resource
    private BuildingFunc buildingFunc;
    @Resource
    private FloorMapper floorMapper;
    @Resource
    private FloorFunc floorFunc;
    @Resource
    private RoomTypeFunc roomTypeFunc;
    @Resource
    private RoomMapper roomMapper;
    @Resource
    private RoomFunc roomFunc;
    @Resource
    private BedMapper bedMapper;
    @Resource
    private BedFunc bedFunc;
    @Resource
    private ElderFunc elderFunc;
    @Resource
    private PageUtil pageUtil;

    @Override
    public Result getNoBedTreeAndPageBedByKey() {
        // 获取所有未被删除的楼栋
        List<Building> listNotDelBuilding = buildingFunc.listNotDelBuilding();
        // 获取所有未被删除的楼层
        List<Floor> listNotDelFloor = floorFunc.listNotDelFloor();
        // 获取所有未被删除的房间
        List<Room> listNotDelRoom = roomFunc.listNotDelRoom();
        // 生成楼栋树
        List<TreeVo> buildingTree = buildingFunc.generateBuildingSimpleTree(listNotDelBuilding, listNotDelFloor, listNotDelRoom, null, false);
//        List<BuildingVo> buildingTree = buildingFunc.generateBuildingTree(listNotDelBuilding, listNotDelFloor, listNotDelRoom, null, false);
        return Result.success(buildingTree);
    }

    @Override
    public Result pageBedByKey(PageBedByKeyQuery query) {
        // 获取所有未被删除的楼栋
        List<Building> listNotDelBuilding = buildingFunc.listNotDelBuilding();
        // 获取所有未被删除的楼层
        List<Floor> listNotDelFloor = floorFunc.listNotDelFloor();
        // 获取所有未被删除的房间
        List<Room> listNotDelRoom = roomFunc.listNotDelRoom();
        // 获取所有未被删除的床位
        List<Bed> listNotDelBed = bedFunc.listNotDelBed();
        // 根据条件过滤床位
        List<PageBedByKeyVo> pageBedByKeyVoList = bedFunc.filterBedByKey(listNotDelBuilding, listNotDelFloor, listNotDelRoom, listNotDelBed, query);
        // 封装返回数据
        PageResult<PageBedByKeyVo> pageResult = pageUtil.packPageResultData(pageBedByKeyVoList, query.getPageNum(), query.getPageSize());
        return Result.success(pageResult);
    }

    @Override
    public Result addBuilding(OperateBuildingQuery query) {
        // 判断楼栋是否已存在
        AssertUtil.isNull(buildingFunc.getBuildingByName(query.getName()), ExceptionEnum.BUILDING_REPEAT);
        // 判断楼栋是否已达到总数限制
        AssertUtil.notTrue(buildingFunc.checkBuildingTotal(), ExceptionEnum.BUILDING_OUT);
        // 初始化楼栋
        query.setId(null);
        Building building = BeanUtil.toBean(query, Building.class);
        building.setDelFlag(YesNoEnum.NO.getCode());
        // 新增
        buildingMapper.insert(building);
        return Result.success();
    }

    @Override
    public Result getBuildingById(Long buildingId) {
        // 根据编号获取楼栋
        Building building = buildingMapper.selectById(buildingId);
        // 判断是否为空
        AssertUtil.notNull(building, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(BeanUtil.toBean(building, OperateBuildingVo.class));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result editBuilding(OperateBuildingQuery query) {
        lockBuilding(query.getId());
        // 判断楼栋是否已存在
        Building buildingByName = buildingFunc.getBuildingByName(query.getName());
        boolean checkName = buildingByName != null && !Objects.equals(buildingByName.getId(), query.getId());
        AssertUtil.notTrue(checkName, ExceptionEnum.BUILDING_REPEAT);
        // 封装修改
        Building building = BeanUtil.toBean(query, Building.class);
        // 修改
        buildingMapper.updateById(building);
        return Result.success();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result addFloor(OperateFloorQuery query) {
        Building parent = lockBuilding(query.getBuildingId());
        query.setFloorLimit(parent.getFloorNum());
        // 判断楼层在该楼栋是否已存在
        AssertUtil.isNull(floorFunc.getFloorByName(query), ExceptionEnum.FLOOR_REPEAT);
        // 判断楼层是否已达到总数限制
        AssertUtil.notTrue(floorFunc.checkFloorTotal(query), ExceptionEnum.FLOOR_OUT);
        // 初始化楼层
        query.setId(null);
        Floor floor = BeanUtil.toBean(query, Floor.class);
        floor.setDelFlag(YesNoEnum.NO.getCode());
        // 新增
        floorMapper.insert(floor);
        return Result.success();
    }

    @Override
    public Result getFloorById(Long floorId) {
        // 根据编号获取楼层
        Floor floor = floorMapper.selectById(floorId);
        // 判断是否为空
        AssertUtil.notNull(floor, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(BeanUtil.toBean(floor, OperateFloorVo.class));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result editFloor(OperateFloorQuery query) {
        Floor stored = lockFloorPath(query.getId());
        requireSameParent(stored.getBuildingId(), query.getBuildingId());
        // 判断楼层在该楼栋是否已存在
        Floor floorByName = floorFunc.getFloorByName(query);
        boolean checkName = floorByName != null && !Objects.equals(floorByName.getId(), query.getId());
        AssertUtil.notTrue(checkName, ExceptionEnum.FLOOR_REPEAT);
        // 封装修改
        Floor floor = BeanUtil.toBean(query, Floor.class);
        // 修改
        floorMapper.updateById(floor);
        return Result.success();
    }

    @Override
    public Result listRoomType() {
        return Result.success(BeanUtil.copyToList(roomTypeFunc.listNotDelRoomType(null), OperateRoomTypeVo.class));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result addRoom(OperateRoomQuery query) {
        Floor parent = lockFloorPath(query.getFloorId());
        query.setRoomLimit(parent.getRoomNum());
        // 判断房间在该楼层是否已存在
        AssertUtil.isNull(roomFunc.getRoomByName(query), ExceptionEnum.ROOM_REPEAT);
        // 判断楼层是否已达到总数限制
        AssertUtil.notTrue(roomFunc.checkRoomTotal(query), ExceptionEnum.ROOM_OUT);
        // 初始化楼层
        query.setId(null);
        Room room = BeanUtil.toBean(query, Room.class);
        room.setDelFlag(YesNoEnum.NO.getCode());
        // 新增
        roomMapper.insert(room);
        return Result.success();
    }

    @Override
    public Result getRoomById(Long roomId) {
        // 根据编号获取房间
        Room room = roomMapper.selectById(roomId);
        // 判断是否为空
        AssertUtil.notNull(room, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(BeanUtil.toBean(room, OperateRoomVo.class));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result editRoom(OperateRoomQuery query) {
        Room stored = lockRoomPath(query.getId());
        requireSameParent(stored.getFloorId(), query.getFloorId());
        // 判断楼层在该楼栋是否已存在
        Room roomByName = roomFunc.getRoomByName(query);
        boolean checkName = roomByName != null && !Objects.equals(roomByName.getId(), query.getId());
        AssertUtil.notTrue(checkName, ExceptionEnum.ROOM_REPEAT);
        // 封装修改
        Room room = BeanUtil.toBean(query, Room.class);
        // 修改
        roomMapper.updateById(room);
        return Result.success();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result deleteNode(DeleteNodeQuery query) {
        Long id = query.getId();
        String mark = query.getMark();
        // Every structural writer locks the same ancestor before discovering descendants.
        // Clinical flows only lock elder -> bed and never acquire these parent locks.
        List<Room> rooms;
        if (Objects.equals(mark, MarkEnum.BUILDING.getMark())) {
            lockBuilding(id);
            List<Long> floors = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                    .eq(Floor::getBuildingId, id).eq(Floor::getDelFlag, YesNoEnum.NO.getCode())
                    .orderByAsc(Floor::getId).last("FOR UPDATE")).stream().map(Floor::getId).collect(Collectors.toList());
            rooms = floors.isEmpty() ? Collections.emptyList() : roomMapper.selectList(new LambdaQueryWrapper<Room>()
                    .in(Room::getFloorId, floors).eq(Room::getDelFlag, YesNoEnum.NO.getCode())
                    .orderByAsc(Room::getId).last("FOR UPDATE"));
        } else if (Objects.equals(mark, MarkEnum.FLOOR.getMark())) {
            lockFloorPath(id);
            rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                    .eq(Room::getFloorId, id).eq(Room::getDelFlag, YesNoEnum.NO.getCode())
                    .orderByAsc(Room::getId).last("FOR UPDATE"));
        } else if (Objects.equals(mark, MarkEnum.ROOM.getMark())) {
            rooms = Collections.singletonList(lockRoomPath(id));
        } else {
            throw new BusinessRuntimeException(ExceptionEnum.NODE_MARK_NOT_EXIST);
        }
        List<Long> roomIds = rooms.stream().map(Room::getId).collect(Collectors.toList());
        if (!roomIds.isEmpty()) {
            List<Long> beds = bedMapper.selectList(new LambdaQueryWrapper<Bed>()
                    .in(Bed::getRoomId, roomIds).eq(Bed::getDelFlag, YesNoEnum.NO.getCode()))
                    .stream().map(Bed::getId).collect(Collectors.toList());
            bedFunc.lockEmptyBeds(beds);
        }
        // 删除楼栋节点
        if (Objects.equals(mark, MarkEnum.BUILDING.getMark())) {
            buildingFunc.deleteBuildingNode(id);
            // 删除楼层节点
        } else if (Objects.equals(mark, MarkEnum.FLOOR.getMark())) {
            floorFunc.deleteFloorNode(null, id);
            // 删除房间节点
        } else if (Objects.equals(mark, MarkEnum.ROOM.getMark())) {
            roomFunc.deleteRoomNode(null, id);
            // 该节点标记不存在
        } else {
            AssertUtil.notTrue(true, ExceptionEnum.NODE_MARK_NOT_EXIST);
        }
        return Result.success();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result addBed(OperateBedQuery query) {
        Room parent = lockRoomPath(query.getRoomId());
        query.setBedLimit(parent.getBedNum());
        // 判断楼层在该楼栋是否已存在
        AssertUtil.isNull(bedFunc.getBedByName(query), ExceptionEnum.BED_REPEAT);
        // 判断楼层是否已达到总数限制
        AssertUtil.notTrue(bedFunc.checkBedTotal(query), ExceptionEnum.BED_OUT);
        // 初始化楼层
        query.setId(null);
        Bed bed = BeanUtil.toBean(query, Bed.class);
        bed.setDelFlag(YesNoEnum.NO.getCode());
        bed.setBedFlag(BedEnum.IDLE.getStatus());
        // 新增
        bedMapper.insert(bed);
        return Result.success();
    }

    @Override
    public Result getBedById(Long bedId) {
        // 根据编号获取床位
        Bed bed = bedMapper.selectById(bedId);
        // 判断是否为空
        AssertUtil.notNull(bed, ExceptionEnum.DATA_NOT_EXIST);
        return Result.success(BeanUtil.toBean(bed, OperateBedVo.class));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result editBed(OperateBedQuery query) {
        Bed snapshot = bedMapper.selectById(query.getId());
        if (snapshot == null) throw new BusinessRuntimeException(409, "床位不存在或已删除");
        lockRoomPath(snapshot.getRoomId());
        Bed stored = bedLifecycle.requireBed(bedLifecycle.lockBeds(query.getId()), query.getId());
        requireSameParent(snapshot.getRoomId(), stored.getRoomId());
        requireSameParent(stored.getRoomId(), query.getRoomId());
        // 判断楼层在该楼栋是否已存在
        Bed bedByName = bedFunc.getBedByName(query);
        boolean checkName = bedByName != null && !Objects.equals(bedByName.getId(), query.getId());
        AssertUtil.notTrue(checkName, ExceptionEnum.BED_REPEAT);
        // 封装修改
        Bed bed = BeanUtil.toBean(query, Bed.class);
        // 修改
        bedMapper.updateById(bed);
        return Result.success();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result deleteBed(Long bedId) {
        bedFunc.lockEmptyBeds(Collections.singletonList(bedId));
        // 封装修改
        Bed bed = new Bed();
        bed.setId(bedId);
        bed.setDelFlag(YesNoEnum.YES.getCode());
        // 修改
        bedMapper.updateById(bed);
        return Result.success();
    }

    private Building lockBuilding(Long id) {
        if (id == null) throw new BusinessRuntimeException(409, "楼栋不存在或已删除");
        Building building = buildingMapper.selectOne(new LambdaQueryWrapper<Building>()
                .eq(Building::getId, id).last("FOR UPDATE"));
        if (building == null || !YesNoEnum.NO.getCode().equals(building.getDelFlag()))
            throw new BusinessRuntimeException(409, "楼栋不存在或已删除");
        return building;
    }

    private Floor lockFloorPath(Long id) {
        Floor snapshot = id == null ? null : floorMapper.selectById(id);
        if (snapshot == null) throw new BusinessRuntimeException(409, "楼层不存在或已删除");
        lockBuilding(snapshot.getBuildingId());
        Floor floor = floorMapper.selectOne(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getId, id).last("FOR UPDATE"));
        if (floor == null || !YesNoEnum.NO.getCode().equals(floor.getDelFlag()))
            throw new BusinessRuntimeException(409, "楼层不存在或已删除");
        requireSameParent(snapshot.getBuildingId(), floor.getBuildingId());
        return floor;
    }

    private Room lockRoomPath(Long id) {
        Room snapshot = id == null ? null : roomMapper.selectById(id);
        if (snapshot == null) throw new BusinessRuntimeException(409, "房间不存在或已删除");
        lockFloorPath(snapshot.getFloorId());
        Room room = roomMapper.selectOne(new LambdaQueryWrapper<Room>()
                .eq(Room::getId, id).last("FOR UPDATE"));
        if (room == null || !YesNoEnum.NO.getCode().equals(room.getDelFlag()))
            throw new BusinessRuntimeException(409, "房间不存在或已删除");
        requireSameParent(snapshot.getFloorId(), room.getFloorId());
        return room;
    }

    private void requireSameParent(Long stored, Long requested) {
        if (!Objects.equals(stored, requested))
            throw new BusinessRuntimeException(409, "不能修改楼层、房间或床位的上级节点");
    }
}
