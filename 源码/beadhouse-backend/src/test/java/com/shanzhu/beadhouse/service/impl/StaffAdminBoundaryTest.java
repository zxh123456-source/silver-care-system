package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.util.RedisUtil;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.OperateStaffQuery;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffAdminBoundaryTest {
    @Mock StaffMapper staffMapper;
    @Mock StaffFunc staffFunc;
    @Mock RedisUtil redisUtil;
    @Mock AttachmentAccessService attachmentAccessService;
    @InjectMocks StaffServiceImpl service;

    @Test void cannotCreateReservedAdministratorOrPromoteOrdinaryStaff() {
        OperateStaffQuery query = new OperateStaffQuery();
        query.setRoleId(1L);
        forbidden(() -> service.addStaff(query));
        query.setId(4L);
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff(4L, 5L));
        forbidden(() -> service.editStaff(query));
        verify(staffMapper, never()).insert(any());
        verify(staffMapper, never()).updateById(any());
        verifyNoInteractions(staffFunc, redisUtil);
    }

    @Test void protectsEveryAdministratorByRoleInsteadOfOnlyTheSeedAccountId() {
        when(staffMapper.selectById(42L)).thenReturn(staff(42L, 1L));
        when(staffMapper.selectByIdForUpdate(42L)).thenReturn(staff(42L, 1L));
        OperateStaffQuery query = new OperateStaffQuery();
        query.setId(42L);
        query.setRoleId(5L);
        forbidden(() -> service.getStaffById(42L));
        forbidden(() -> service.editStaff(query));
        forbidden(() -> service.leaveStaff(42L));
        verify(staffMapper, never()).updateById(any());
        verifyNoInteractions(staffFunc, redisUtil);
    }

    @Test void ordinaryStaffDetailsAndDepartureRemainAvailable() {
        when(staffMapper.selectById(4L)).thenReturn(staff(4L, 5L));
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff(4L, 5L));
        assertThat(service.getStaffById(4L).getCode()).isEqualTo(200);
        assertThat(service.leaveStaff(4L).getCode()).isEqualTo(200);
        verify(staffMapper).updateById(argThat(value -> value.getId().equals(4L) && "Y".equals(value.getLeaveFlag())));
        verify(attachmentAccessService).retire("STAFF", 4L);
        verify(redisUtil).deleteObject("login:4");
    }

    @Test void missingStaffCannotBeReportedAsSuccessfullyChanged() {
        OperateStaffQuery query = new OperateStaffQuery();
        query.setId(99L);
        assertThatThrownBy(() -> service.editStaff(query)).isInstanceOfSatisfying(
                BusinessRuntimeException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> service.leaveStaff(99L)).isInstanceOfSatisfying(
                BusinessRuntimeException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verify(staffMapper, never()).updateById(any());
        verifyNoInteractions(staffFunc, redisUtil);
    }

    private Staff staff(Long id, Long role) {
        Staff value = new Staff();
        value.setId(id);
        value.setRoleId(role);
        return value;
    }

    private void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(403));
    }
}
