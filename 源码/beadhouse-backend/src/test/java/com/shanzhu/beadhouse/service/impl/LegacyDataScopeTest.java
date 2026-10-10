package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.*;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LegacyDataScopeTest {
    private final AiDataScopeService scope = mock(AiDataScopeService.class);
    private <T> T scoped(T service) {
        ReflectionTestUtils.setField(service, "dataScopeService", scope);
        doThrow(new BusinessRuntimeException(403, "无权访问该老人数据")).when(scope).assertElderAccess(8L);
        return service;
    }
    private void denied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessRuntimeException.class).hasMessageContaining("无权访问");
    }
    @Test void elderDetailDeniedBeforeReadingPersonalData() {
        ElderRecordServiceImpl service = scoped(new ElderRecordServiceImpl());
        ElderMapper mapper = mock(ElderMapper.class); ReflectionTestUtils.setField(service, "elderMapper", mapper);
        denied(() -> service.getElderRecordById(8L)); verifyNoInteractions(mapper);
    }
    @Test void rechargeDeniedBeforeBalanceReadOrWrite() {
        DepositRechargeServiceImpl service = scoped(new DepositRechargeServiceImpl());
        ElderMapper mapper = mock(ElderMapper.class); ReflectionTestUtils.setField(service, "elderMapper", mapper);
        RechargeQuery query = new RechargeQuery(); query.setElderId(8L);
        denied(() -> service.recharge(query)); verifyNoInteractions(mapper);
    }
    @Test void reservationExecutionChecksStoredOwnerBeforeCharging() {
        NurseReserveServiceImpl service = scoped(new NurseReserveServiceImpl());
        NurseReserveMapper mapper = mock(NurseReserveMapper.class);
        ElderFunc elder = mock(ElderFunc.class); ConsumeFunc consume = mock(ConsumeFunc.class);
        ReflectionTestUtils.setField(service, "nurseReserveMapper", mapper);
        ReflectionTestUtils.setField(service, "elderFunc", elder); ReflectionTestUtils.setField(service, "consumeFunc", consume);
        NurseReserve record = new NurseReserve(); record.setId(99L); record.setElderId(8L);
        when(mapper.selectById(99L)).thenReturn(record);
        ExecuteNurseReserveQuery query = new ExecuteNurseReserveQuery(); query.setId(99L);
        denied(() -> service.executeNurseReserve(query));
        verify(mapper, never()).updateById(any()); verifyNoInteractions(elder, consume);
    }
    @Test void orderExecutionChecksStoredOwnerBeforeCharging() {
        OrderServiceImpl service = scoped(new OrderServiceImpl());
        OrderMapper mapper = mock(OrderMapper.class); ElderFunc elder = mock(ElderFunc.class); ConsumeFunc consume = mock(ConsumeFunc.class);
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        ReflectionTestUtils.setField(service, "elderFunc", elder); ReflectionTestUtils.setField(service, "consumeFunc", consume);
        Order record = new Order(); record.setId(99L); record.setElderId(8L); when(mapper.selectById(99L)).thenReturn(record);
        SendOrderQuery query = new SendOrderQuery(); query.setId(99L);
        denied(() -> service.sendOrder(query));
        verify(mapper, never()).updateById(any()); verifyNoInteractions(elder, consume);
    }
    @Test void accidentDetailsAndDeletionUseRecordOwner() {
        AccidentServiceImpl service = scoped(new AccidentServiceImpl());
        AccidentMapper mapper = mock(AccidentMapper.class); AccidentFunc func = mock(AccidentFunc.class);
        ReflectionTestUtils.setField(service, "accidentMapper", mapper); ReflectionTestUtils.setField(service, "accidentFunc", func);
        Accident record = new Accident(); record.setElderId(8L);
        when(mapper.selectById(99L)).thenReturn(record); when(func.checkAccident(99L, true)).thenReturn(record);
        denied(() -> service.getAccidentById(99L)); denied(() -> service.deleteAccident(99L));
        verify(mapper, never()).getAccidentById(anyLong()); verify(mapper, never()).updateById(any());
    }
    @Test void outwardReturnAndContactsCannotBypassScope() {
        OutwardServiceImpl service = scoped(new OutwardServiceImpl());
        OutwardFunc func = mock(OutwardFunc.class); OutwardMapper mapper = mock(OutwardMapper.class);
        EmergencyContactMapper contacts = mock(EmergencyContactMapper.class);
        ReflectionTestUtils.setField(service, "outwardFunc", func); ReflectionTestUtils.setField(service, "outwardMapper", mapper);
        ReflectionTestUtils.setField(service, "emergencyContactMapper", contacts);
        Outward record = new Outward(); record.setElderId(8L); when(func.checkOutward(99L,false)).thenReturn(record);
        RecordReturnQuery query = new RecordReturnQuery(); query.setId(99L);
        denied(() -> service.recordReturn(query)); denied(() -> service.listContactByElderId(8L));
        verifyNoInteractions(mapper, contacts);
    }
    @Test void elderEditAndDeleteAliasesAreProtected() {
        IntentionServiceImpl edit = scoped(new IntentionServiceImpl());
        ElderFunc func = mock(ElderFunc.class); ReflectionTestUtils.setField(edit, "elderFunc", func);
        OperateIntentionQuery query = new OperateIntentionQuery(); query.setId(8L);
        denied(() -> edit.editIntention(query)); verifyNoInteractions(func);
        CheckContractServiceImpl delete = scoped(new CheckContractServiceImpl());
        ElderMapper mapper = mock(ElderMapper.class); ReflectionTestUtils.setField(delete,"elderMapper",mapper);
        denied(() -> delete.deleteCheckContract(8L)); verifyNoInteractions(mapper);
    }
    @Test void retirementFeeAuditCannotModifyForeignElder() {
        RetreatAuditServiceImpl service = scoped(new RetreatAuditServiceImpl());
        ElderMapper mapper = mock(ElderMapper.class); ReflectionTestUtils.setField(service, "elderMapper", mapper);
        AuditElderFeeQuery query = new AuditElderFeeQuery(); query.setElderId(8L);
        denied(() -> service.auditElderFee(query)); verifyNoInteractions(mapper);
    }
}
