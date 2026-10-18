-- ONLY after importing all four initialization scripts into a disposable CI database.
-- Administrator password has already been randomized by DemoAccountInitializer.
UPDATE staff caregiver JOIN staff administrator ON administrator.id=1
SET caregiver.pass=administrator.pass, caregiver.name='E2E护工', caregiver.leave_flag='N',
    caregiver.id_num='110101199001010000', caregiver.address='浏览器回归测试地址', caregiver.email='e2ecaregiver@example.com'
WHERE caregiver.id=4;
UPDATE elder SET check_flag='退住' WHERE id NOT IN(4,8);
UPDATE elder SET name='E2E可见',check_flag='入住',balance=100 WHERE id=4;
UPDATE service_item SET name='E2E计费服务',charge_method='按次',price=12.50,need_date=10,del_flag='N' WHERE id=2;
UPDATE elder SET name='E2E隔离',check_flag='入住' WHERE id=8;
DELETE FROM elder_staff_assignment;
INSERT INTO role_auth(role_id,auth_id,create_id,create_time,update_id,update_time)
SELECT 5,a.id,1,NOW(),1,NOW() FROM auth a WHERE a.id IN(43,44,46,48,50)
AND NOT EXISTS(SELECT 1 FROM role_auth r WHERE r.role_id=5 AND r.auth_id=a.id);
INSERT INTO care_note(elder_id,event_time,source_text,follow_up,status)
WITH RECURSIVE sequence AS(SELECT 1 AS n UNION ALL SELECT n+1 FROM sequence WHERE n<201)
SELECT 4,DATE_SUB(NOW(),INTERVAL 3 DAY),'E2E护理来源',
       IF(n=1,'E2E交接任务',CONCAT('E2E分页任务',n)),'PENDING' FROM sequence;
