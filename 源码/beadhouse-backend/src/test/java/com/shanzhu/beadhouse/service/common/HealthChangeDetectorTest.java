package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.po.HealthData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthChangeDetectorTest {
    private final HealthChangeDetector detector = new HealthChangeDetector();

    @BeforeEach
    void configureThresholds() {
        ReflectionTestUtils.setField(detector, "temperatureChange", 1.0d);
        ReflectionTestUtils.setField(detector, "heartRateChange", 20);
        ReflectionTestUtils.setField(detector, "bloodPressureChange", 20);
        ReflectionTestUtils.setField(detector, "bloodOxygenChange", 3);
        ReflectionTestUtils.setField(detector, "weightChange", 2.0d);
        ReflectionTestUtils.setField(detector, "bloodGlucoseChange", 2.0d);
    }

    @Test
    void comparesMostRecentTwoNonNullValuesAcrossPartialRecords() {
        HealthData first = new HealthData();
        first.setTemperature(36.5);
        first.setFastingBloodGlucose(5.2);
        HealthData partial = new HealthData();
        partial.setWeight(65.0);
        HealthData latest = new HealthData();
        latest.setTemperature(37.7);
        latest.setFastingBloodGlucose(8.0);

        List<String> reminders = detector.detect(Arrays.asList(first, partial, latest));

        assertThat(reminders).contains("体温由 36.5 变为 37.7℃", "空腹血糖由 5.2 变为 8.0mmol/L");
    }
}
