package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.util.RedisUtil;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.OperateStaffQuery;
import com.shanzhu.beadhouse.service.common.StaffFunc;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class StaffSessionInvalidationTest {
    @Mock StaffMapper staffMapper;
    @Mock StaffFunc staffFunc;
    @Mock RedisUtil redisUtil;
    @Mock AttachmentAccessService attachmentAccessService;
    @InjectMocks StaffServiceImpl service;
    @Test void staffEditInvalidatesPreviouslyCachedRoleOnlyAfterSuccessfulWrite() {
        Staff staff=new Staff(); staff.setId(4L); staff.setRoleId(5L);
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff);
        OperateStaffQuery query=new OperateStaffQuery(); query.setId(4L); query.setRoleId(7L);
        assertThat(service.editStaff(query).getCode()).isEqualTo(200);
        InOrder order=inOrder(staffMapper,redisUtil);
        order.verify(staffMapper).updateById(any()); order.verify(redisUtil).deleteObject("login:4");
    }
    @Test void failedEditDoesNotInvalidateTheSession() {
        Staff staff=new Staff(); staff.setId(4L); staff.setRoleId(5L);
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff);
        OperateStaffQuery query=new OperateStaffQuery(); query.setId(4L); query.setRoleId(7L);
        doThrow(new IllegalStateException("test write failure")).when(staffMapper).updateById(any());
        assertThatThrownBy(() -> service.editStaff(query)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redisUtil);
    }
    @Test void omittedAvatarPreservesLegacyValueAndExplicitEmptyAvatarClearsIt() {
        Staff staff = new Staff(); staff.setId(4L); staff.setRoleId(5L);
        staff.setAvator("legacy-unrecognized-url");
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff);
        when(attachmentAccessService.bind("STAFF", 4L, "legacy-unrecognized-url", "legacy-unrecognized-url"))
                .thenReturn("legacy-unrecognized-url");
        when(attachmentAccessService.bind("STAFF", 4L, "legacy-unrecognized-url", "")).thenReturn("");
        OperateStaffQuery query = new OperateStaffQuery(); query.setId(4L); query.setRoleId(5L);

        service.editStaff(query);
        verify(staffMapper).updateById(argThat(value -> "legacy-unrecognized-url".equals(value.getAvator())
                && value.getPass() == null && value.getLeaveFlag() == null));

        query.setAvator("");
        service.editStaff(query);
        verify(attachmentAccessService).bind("STAFF", 4L, "legacy-unrecognized-url", "");
        verify(staffMapper).updateById(argThat(value -> "".equals(value.getAvator())
                && value.getPass() == null && value.getLeaveFlag() == null));
        verify(redisUtil, times(2)).deleteObject("login:4");
    }

    @Test void failedAvatarBindingDoesNotSaveTheProfileOrInvalidateTheSession() {
        Staff staff = new Staff(); staff.setId(4L); staff.setRoleId(5L); staff.setAvator("/file/20/content");
        when(staffMapper.selectByIdForUpdate(4L)).thenReturn(staff);
        OperateStaffQuery query = new OperateStaffQuery(); query.setId(4L); query.setRoleId(7L);
        query.setAvator("/file/21/content");
        when(attachmentAccessService.bind("STAFF", 4L, "/file/20/content", "/file/21/content"))
                .thenThrow(new IllegalStateException("reference write failed"));

        assertThatThrownBy(() -> service.editStaff(query)).isInstanceOf(IllegalStateException.class);

        verify(staffMapper, never()).updateById(any());
        verifyNoInteractions(redisUtil);
    }
}
