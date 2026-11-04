package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.po.*;
import com.shanzhu.beadhouse.entity.query.*;
import com.shanzhu.beadhouse.service.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BillingInputBindingTest {
    @Test void fractionalServiceQuantityIsNotSilentlyTruncatedByJsonBinding() throws Exception {
        AddNurseReserveQuery query = new ObjectMapper().readValue("{\"elderId\":4,\"serviceId\":2,\"frequency\":2.5}", AddNurseReserveQuery.class);
        NurseReserveServiceImpl service = new NurseReserveServiceImpl();
        ServiceItemMapper catalog = mock(ServiceItemMapper.class);
        NurseReserveMapper reservations = mock(NurseReserveMapper.class);
        ServiceItem item = new ServiceItem(); item.setName("test"); item.setPrice(new BigDecimal("10")); item.setNeedDate(10); item.setChargeMethod("按次"); item.setDelFlag("N");
        when(catalog.selectById(2L)).thenReturn(item);
        ReflectionTestUtils.setField(service, "dataScopeService", mock(AiDataScopeService.class));
        ReflectionTestUtils.setField(service, "serviceItemMapper", catalog);
        ReflectionTestUtils.setField(service, "nurseReserveMapper", reservations);
        assertThatThrownBy(() -> service.addNurseReserve(query)).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
        verifyNoInteractions(reservations);
    }

    @Test void fractionalMealQuantityIsNotSilentlyTruncatedByJsonBinding() throws Exception {
        AddOrderQuery query = new ObjectMapper().readValue("{\"elderId\":4,\"dineDate\":\"2026-10-18\",\"dineType\":\"送餐\",\"orderDishesList\":[{\"dishesId\":1,\"orderNum\":1.5}]}", AddOrderQuery.class);
        OrderServiceImpl service = new OrderServiceImpl();
        OrderMapper orders = mock(OrderMapper.class);
        DishesMapper catalog = mock(DishesMapper.class);
        Dishes dish = new Dishes(); dish.setId(1L); dish.setName("test"); dish.setPrice(new BigDecimal("10")); dish.setDelFlag("N");
        when(catalog.listSetDishesByElderId(4L)).thenReturn(Collections.emptyList());
        when(catalog.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(dish));
        ReflectionTestUtils.setField(service, "dataScopeService", mock(AiDataScopeService.class));
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "dishesMapper", catalog);
        ReflectionTestUtils.setField(service, "orderDishesFunc", mock(OrderDishesFunc.class));
        assertThatThrownBy(() -> service.addOrder(query)).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
        verifyNoInteractions(orders);
    }
}
