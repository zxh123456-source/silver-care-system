package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.ElderStaffAssignmentMapper;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDataScopeServiceTest {
    @Mock private AuthorityAssert authorityAssert;
    @Mock private ElderStaffAssignmentMapper assignmentMapper;
    @Mock private StaffMapper staffMapper;
    @InjectMocks private AiDataScopeService service;

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(service, "adminRoleId", 1L);
    }

    @Test
    void deniesUnassignedElderForNonAdmin() {
        LoginUserVo user = new LoginUserVo(); user.setId(4L); user.setRoleId(5L);
        when(authorityAssert.getLoginUserInfo()).thenReturn(user);
        when(authorityAssert.getLoginUserId()).thenReturn(4L);
        when(assignmentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.assertElderAccess(17L))
                .isInstanceOf(BusinessRuntimeException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void administratorBypassesAssignmentLookup() {
        LoginUserVo user = new LoginUserVo(); user.setId(1L); user.setRoleId(1L);
        when(authorityAssert.getLoginUserInfo()).thenReturn(user);

        assertThatCode(() -> service.assertElderAccess(17L)).doesNotThrowAnyException();
    }
}
