package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanzhu.beadhouse.entity.query.AddOrderQuery;
import com.shanzhu.beadhouse.entity.query.AddOutboundRecordQuery;
import com.shanzhu.beadhouse.entity.query.AddWarehouseRecordQuery;
import com.shanzhu.beadhouse.entity.query.OperateCheckContractQuery;
import io.swagger.annotations.ApiModelProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SwaggerExamplesTest {
    @Test
    void structuredRequestExamplesAreValidJsonArrays() {
        ObjectMapper mapper = new ObjectMapper();
        for (Class<?> type : new Class<?>[]{AddOrderQuery.class, AddOutboundRecordQuery.class,
                AddWarehouseRecordQuery.class, OperateCheckContractQuery.class}) {
            for (Field field : type.getDeclaredFields()) {
                ApiModelProperty property = field.getAnnotation(ApiModelProperty.class);
                if (property == null || !property.example().startsWith("[")) continue;
                JsonNode example;
                try {
                    // Springfox writes structured examples directly into the public Swagger document.
                    example = mapper.readTree(property.example());
                } catch (Exception invalid) {
                    fail("Invalid structured Swagger example: " + type.getSimpleName() + "." + field.getName());
                    return;
                }
                assertTrue(example.isArray(), "Structured request example must remain an array");
            }
        }
    }
}
