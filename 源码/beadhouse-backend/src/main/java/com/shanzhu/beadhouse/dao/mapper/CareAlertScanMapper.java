package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.po.CareAlertScanCheckpoint;
import com.shanzhu.beadhouse.entity.po.MedicationPlan;
import org.apache.ibatis.annotations.*;
import java.sql.Date;
import java.util.List;

public interface CareAlertScanMapper {
    // Acquire an exclusive duplicate-key lock directly, avoiding shared-to-exclusive lock upgrades.
    @Insert("INSERT INTO ai_alert_scan_checkpoint(id,scan_date,plan_cursor,target_date) VALUES(1,#{start},0,#{target}) ON DUPLICATE KEY UPDATE id=id")
    int initialize(@Param("start") Date start,@Param("target") Date target);
    @Select("SELECT * FROM ai_alert_scan_checkpoint WHERE id=1 FOR UPDATE")
    CareAlertScanCheckpoint lock();
    @Select("SELECT COUNT(*) FROM ai_alert_scan_checkpoint WHERE id=1 AND (lease_until IS NULL OR lease_until<=NOW(3)) AND (next_scan_at IS NULL OR next_scan_at<=NOW(3))")
    int claimable();
    @Update("UPDATE ai_alert_scan_checkpoint SET lease_owner=#{owner},lease_token=lease_token+1,lease_until=TIMESTAMPADD(SECOND,#{seconds},NOW(3)),scan_date=#{start},target_date=#{target},plan_cursor=#{cursor} WHERE id=1")
    int claim(@Param("owner") String owner,@Param("seconds") int seconds,@Param("start") Date start,@Param("target") Date target,@Param("cursor") long cursor);
    @Select("SELECT COUNT(*) FROM ai_alert_scan_checkpoint WHERE id=1 AND lease_owner=#{owner} AND lease_token=#{token} AND lease_until>NOW(3)")
    @Options(flushCache=Options.FlushCachePolicy.TRUE)
    int valid(@Param("owner") String owner,@Param("token") long token);
    @Select("SELECT p.* FROM medication_plan p JOIN elder e ON e.id=p.elder_id WHERE p.id>#{cursor} AND p.start_date<=#{day} AND (p.end_date IS NULL OR p.end_date>=#{day}) AND e.check_flag IN ('入住','退住审核') ORDER BY p.id LIMIT #{limit}")
    List<MedicationPlan> plans(@Param("day") Date day,@Param("cursor") long cursor,@Param("limit") int limit);
    @Update("UPDATE ai_alert_scan_checkpoint SET scan_date=#{scanDate},plan_cursor=#{planCursor},last_completed_date=#{lastCompletedDate},last_success_at=NOW(3),last_error=NULL WHERE id=1")
    int advance(CareAlertScanCheckpoint checkpoint);
    @Update("UPDATE ai_alert_scan_checkpoint SET lease_owner=NULL,lease_until=NULL,next_scan_at=TIMESTAMPADD(SECOND,#{delay},NOW(3)) WHERE id=1 AND lease_owner=#{owner} AND lease_token=#{token}")
    int release(@Param("owner") String owner,@Param("token") long token,@Param("delay") int delay);
    @Update("UPDATE ai_alert_scan_checkpoint SET last_error='后台核对暂未完成，将自动重试',lease_owner=NULL,lease_until=NULL,next_scan_at=TIMESTAMPADD(SECOND,60,NOW(3)) WHERE id=1 AND lease_owner=#{owner} AND lease_token=#{token}")
    int failed(@Param("owner") String owner,@Param("token") long token);
}
