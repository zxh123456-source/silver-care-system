-- ONLY after importing all initialization scripts into a disposable CI database.
-- Administrator password has already been randomized by DemoAccountInitializer.
UPDATE staff caregiver JOIN staff administrator ON administrator.id=1
SET caregiver.pass=administrator.pass, caregiver.name='E2E护工', caregiver.leave_flag='N',
    caregiver.id_num='110101199001010000', caregiver.address='浏览器回归测试地址', caregiver.email='e2ecaregiver@example.com'
WHERE caregiver.id=4;
-- Preserve retired duplicate identities and keep bed/application state consistent.
UPDATE elder SET check_flag='已退住',bed_id=NULL,nursing_grade_id=NULL,catering_set_id=NULL
WHERE id NOT IN(4,8) AND check_flag<>'已失效';
UPDATE retreat_apply SET apply_flag='未通过' WHERE apply_flag='待审核';
UPDATE bed SET bed_flag='空闲';
UPDATE elder SET name='E2E可见',check_flag='入住',balance=100 WHERE id=4;
UPDATE service_item SET name='E2E计费服务',charge_method='按次',price=12.50,need_date=10,del_flag='N' WHERE id=2;
UPDATE elder SET name='E2E隔离',check_flag='入住' WHERE id=8;
UPDATE bed b JOIN elder e ON e.bed_id=b.id SET b.bed_flag='入住' WHERE e.id IN(4,8);
INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,create_id,create_time,update_id,update_time)
VALUES(867101,'E2E预订','110101195001010003',76,'女','19900003001','浏览器床位测试地址',0,'咨询中',1,NOW(),1,NOW());
INSERT INTO elder(id,name,id_num,age,sex,phone,address,balance,check_flag,bed_id,create_id,create_time,update_id,update_time)
VALUES(867102,'E2E自动核对','110101195001010004',76,'女','19900003003','浏览器后台核对测试地址',0,'入住',2,1,NOW(),1,NOW());
UPDATE bed SET bed_flag='入住' WHERE id=2;
INSERT INTO medication_plan(id,elder_id,medicine_name,dose_instruction,periods,start_date,end_date,enabled)
VALUES(868901,867102,'E2E自动核对计划','按测试医嘱','早',DATE_SUB(CURRENT_DATE,INTERVAL 1 DAY),DATE_SUB(CURRENT_DATE,INTERVAL 1 DAY),'Y');
DELETE FROM elder_staff_assignment;
INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time)
SELECT 5,a.id,1,NOW(),1,NOW() FROM auth a WHERE a.id IN(31,32,43,44,46,48,50)
AND NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=5 AND r.auth_id=a.id);
INSERT INTO care_note(elder_id,event_time,source_text,follow_up,status)
WITH RECURSIVE sequence AS(SELECT 1 AS n UNION ALL SELECT n+1 FROM sequence WHERE n<201)
SELECT 4,DATE_SUB(NOW(),INTERVAL 3 DAY),'E2E护理来源',
       IF(n=1,'E2E交接任务',CONCAT('E2E分页任务',n)),'PENDING' FROM sequence;
