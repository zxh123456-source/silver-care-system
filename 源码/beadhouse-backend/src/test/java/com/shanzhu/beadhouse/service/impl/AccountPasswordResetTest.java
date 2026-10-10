package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.security.handler.PasswordEncoderImpl;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.util.RedisUtil;
import com.shanzhu.beadhouse.dao.mapper.StaffMapper;
import com.shanzhu.beadhouse.entity.po.Staff;
import com.shanzhu.beadhouse.entity.query.SendCodeQuery;
import com.shanzhu.beadhouse.entity.query.ForgetQuery;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AccountPasswordResetTest {
    @Mock StaffFunc staffFunc;
    @Mock StaffMapper staffMapper;
    @Mock RedisUtil redisUtil;
    @Mock PasswordEncoderImpl passwordEncoder;
    @Mock PasswordResetGuard resetGuard;
    @Mock PasswordResetDelivery resetDelivery;
    @InjectMocks AccountServiceImpl service;

    @Test void phoneCannotObtainCodeOrCreateChallenge() {
        SendCodeQuery query = new SendCodeQuery(); query.setAccount("13900000000");
        assertThat(service.sendCode(query).getCode()).isEqualTo(400);
        verifyNoInteractions(resetGuard, resetDelivery, staffFunc);
    }
    @Test void registeredAndUnknownEmailReturnSameResponseWithoutCode() {
        Staff staff = new Staff(); staff.setEmail("demo@example.com");
        when(staffFunc.getStaffByAccount("demo@example.com")).thenReturn(staff);
        SendCodeQuery query = new SendCodeQuery(); query.setAccount(staff.getEmail());
        Result known = service.sendCode(query);
        query.setAccount("unknown@example.com"); Result unknown = service.sendCode(query);
        assertThat(known.getData()).isNull(); assertThat(unknown.getData()).isNull();
        assertThat(known.getMsg()).isEqualTo(unknown.getMsg());
        verify(resetDelivery).send(eq(staff.getEmail()), matches("[0-9]{6}"));
    }
    @Test void deliveryFailureInvalidatesChallenge() {
        Staff staff = new Staff(); staff.setEmail("demo@example.com");
        when(staffFunc.getStaffByAccount(staff.getEmail())).thenReturn(staff);
        doThrow(new BusinessRuntimeException(503, "发送失败")).when(resetDelivery).send(anyString(), anyString());
        SendCodeQuery query = new SendCodeQuery(); query.setAccount(staff.getEmail());
        assertThatThrownBy(() -> service.sendCode(query)).isInstanceOf(BusinessRuntimeException.class);
        verify(resetGuard).discard(staff.getEmail());
    }
    @Test void invalidCodeCannotUpdatePassword() {
        ForgetQuery query = resetQuery();
        doThrow(new BusinessRuntimeException(400, "无效")).when(resetGuard).consume(query.getAccount(), query.getCode());
        assertThatThrownBy(() -> service.forget(query)).isInstanceOf(BusinessRuntimeException.class);
        verifyNoInteractions(staffMapper, passwordEncoder);
    }
    @Test void rejectsPasswordBeyondBcryptByteLimitBeforeConsumingCode() {
        ForgetQuery query = resetQuery();
        query.setPass("中文密码中文密码中文密码中文密码中文密码中文密码中文密码");
        assertThat(service.forget(query).getCode()).isEqualTo(400);
        verifyNoInteractions(resetGuard, staffMapper);
    }
    @Test void successfulResetConsumesBeforeWriteAndRevokesSession() {
        ForgetQuery query = resetQuery();
        Staff staff = new Staff(); staff.setId(42L); staff.setEmail(query.getAccount());
        when(staffFunc.getStaffByAccount(query.getAccount())).thenReturn(staff);
        when(passwordEncoder.encode(query.getPass())).thenReturn("bcrypt-hash");
        assertThat(service.forget(query).getCode()).isEqualTo(200);
        InOrder order = inOrder(resetGuard, staffMapper, redisUtil);
        order.verify(resetGuard).throttle(eq("verify"), eq(query.getAccount()), anyString());
        order.verify(resetGuard).consume(query.getAccount(), query.getCode());
        order.verify(staffMapper).updateById(staff);
        order.verify(redisUtil).deleteObject("login:42");
        assertThat(staff.getPass()).isEqualTo("bcrypt-hash");
    }
    private ForgetQuery resetQuery() {
        ForgetQuery q = new ForgetQuery(); q.setAccount("demo@example.com"); q.setPass("new-password"); q.setCode("123456"); return q;
    }
}
