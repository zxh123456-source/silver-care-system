package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.po.HealthData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
public class HealthChangeDetector {
    @Value("${ai.health.change.temperature:1.0}")
    private double temperatureChange;
    @Value("${ai.health.change.heart-rate:20}")
    private int heartRateChange;
    @Value("${ai.health.change.blood-pressure:20}")
    private int bloodPressureChange;
    @Value("${ai.health.change.blood-oxygen:3}")
    private int bloodOxygenChange;
    @Value("${ai.health.change.weight:2.0}")
    private double weightChange;
    @Value("${ai.health.change.blood-glucose:2.0}")
    private double bloodGlucoseChange;

    public List<String> detect(List<HealthData> records) {
        List<String> reminders = new ArrayList<>();
        addRecentDouble(reminders, records, "体温", HealthData::getTemperature, temperatureChange, "℃");
        addRecentInteger(reminders, records, "心率", HealthData::getHeartRate, heartRateChange, "次/分");
        addRecentInteger(reminders, records, "收缩压", HealthData::getSystolicBloodPressure, bloodPressureChange, "mmHg");
        addRecentInteger(reminders, records, "舒张压", HealthData::getDiastolicBloodPressure, bloodPressureChange, "mmHg");
        addRecentInteger(reminders, records, "血氧", HealthData::getBloodOxygenSaturation, bloodOxygenChange, "%");
        addRecentDouble(reminders, records, "体重", HealthData::getWeight, weightChange, "kg");
        addRecentDouble(reminders, records, "空腹血糖", HealthData::getFastingBloodGlucose, bloodGlucoseChange, "mmol/L");
        addRecentDouble(reminders, records, "餐后血糖", HealthData::getPostprandialBloodGlucose, bloodGlucoseChange, "mmol/L");
        return reminders;
    }

    private void addRecentInteger(List<String> output, List<HealthData> records, String name,
                                  Function<HealthData, Integer> getter, int threshold, String unit) {
        Integer latest = null;
        Integer previous = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            Integer value = getter.apply(records.get(i));
            if (value == null) continue;
            if (latest == null) latest = value;
            else { previous = value; break; }
        }
        if (previous != null && latest != null && Math.abs(latest - previous) >= threshold) {
            output.add(name + "由 " + previous + " 变为 " + latest + unit);
        }
    }

    private void addRecentDouble(List<String> output, List<HealthData> records, String name,
                                 Function<HealthData, Double> getter, double threshold, String unit) {
        Double latest = null;
        Double previous = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            Double value = getter.apply(records.get(i));
            if (value == null) continue;
            if (latest == null) latest = value;
            else { previous = value; break; }
        }
        if (previous != null && latest != null && Math.abs(latest - previous) >= threshold) {
            output.add(name + "由 " + previous + " 变为 " + latest + unit);
        }
    }
}
