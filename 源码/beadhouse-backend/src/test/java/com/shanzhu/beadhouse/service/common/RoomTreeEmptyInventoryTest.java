package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.base.BuildingVo;
import com.shanzhu.beadhouse.entity.po.Bed;
import com.shanzhu.beadhouse.entity.po.Room;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTreeEmptyInventoryTest {
    @Test
    void anEmptyHomeStillShowsItsAvailableBeds() {
        Room room = new Room();
        room.setId(11L);
        Bed bed = new Bed();
        bed.setId(21L);
        bed.setRoomId(11L);
        bed.setBedFlag("空闲");
        List<BuildingVo.BuildingItem.FloorItem> result = new RoomFunc().generateRoomTree(
                Collections.singletonList(room), Collections.singletonList(bed), Collections.emptyList(), false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBedList()).hasSize(1);
        assertThat(result.get(0).getBedList().get(0).getId()).isEqualTo(21L);
        assertThat(result.get(0).getBedList().get(0).getElderName()).isNull();
    }

    @Test
    void anEmptyNameSearchDoesNotIntroduceUnrelatedEmptyBeds() {
        Room room = new Room();
        room.setId(11L);
        Bed bed = new Bed();
        bed.setId(21L);
        bed.setRoomId(11L);
        assertThat(new RoomFunc().generateRoomTree(Collections.singletonList(room),
                Collections.singletonList(bed), Collections.emptyList(), true)).isEmpty();
    }
}
