package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.util.RedisUtil;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.query.OperateStaffQuery;
import com.shanzhu.beadhouse.service.common.StaffFunc;
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
    @InjectMocks StaffServiceImpl service;
    @Test void staffEditInvalidatesPreviouslyCachedRoleOnlyAfterSuccessfulWrite() {
        OperateStaffQuery query=new OperateStaffQuery(); query.setId(4L); query.setRoleId(7L);
        assertThat(service.editStaff(query).getCode()).isEqualTo(200);
        InOrder order=inOrder(staffMapper,redisUtil);
        order.verify(staffMapper).updateById(any()); order.verify(redisUtil).deleteObject("login:4");
    }
    @Test void failedEditDoesNotInvalidateTheSession() {
        OperateStaffQuery query=new OperateStaffQuery(); query.setId(4L); query.setRoleId(7L);
        doThrow(new IllegalStateException("test write failure")).when(staffMapper).updateById(any());
        assertThatThrownBy(() -> service.editStaff(query)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(redisUtil);
    }
}
