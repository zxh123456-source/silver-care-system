/*
 Navicat Premium Data Transfer

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 80026
 Source Host           : localhost:3306
 Source Schema         : db_beadhouse

 Target Server Type    : MySQL
 Target Server Version : 80026
 File Encoding         : 65001

 Date: 31/10/2024 11:36:42
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for accident
-- ----------------------------
DROP TABLE IF EXISTS `accident`;
CREATE TABLE `accident`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NOT NULL COMMENT '值班护工编号',
  `occur_date` datetime NOT NULL COMMENT '发生时间',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '事故描述',
  `picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '事故图片',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '事故登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of accident
-- ----------------------------

-- ----------------------------
-- Table structure for active
-- ----------------------------
DROP TABLE IF EXISTS `active`;
CREATE TABLE `active`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '活动类别编号',
  `theme` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动主题',
  `name` varchar(25) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动名称',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动内容',
  `address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动地点',
  `organizer` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '组织者姓名',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '组织者电话',
  `active_date` datetime NOT NULL COMMENT '活动日期',
  `active_picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动图片',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '活动表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of active
-- ----------------------------

-- ----------------------------
-- Table structure for active_participant
-- ----------------------------
DROP TABLE IF EXISTS `active_participant`;
CREATE TABLE `active_participant`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `active_id` bigint NOT NULL COMMENT '活动编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '活动参与者表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of active_participant
-- ----------------------------

-- ----------------------------
-- Table structure for active_type
-- ----------------------------
DROP TABLE IF EXISTS `active_type`;
CREATE TABLE `active_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动类型名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '活动类型表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of active_type
-- ----------------------------

-- ----------------------------
-- Table structure for auth
-- ----------------------------
DROP TABLE IF EXISTS `auth`;
CREATE TABLE `auth`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `parent_id` bigint NOT NULL COMMENT '父级编号',
  `title` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限标题',
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限名称',
  `path` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限path',
  `icon` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权限图标',
  `url` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限url',
  `type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权限类别（MENU/BTN）',
  `method` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权限请求方式（GET/POST/PUT/DELETE）',
  `create_id` bigint NULL DEFAULT NULL COMMENT '创建人编号',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_id` bigint NULL DEFAULT NULL COMMENT '修改人编号',
  `update_time` datetime NULL DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 43 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of auth
-- ----------------------------

-- ----------------------------
-- Table structure for base_attachment
-- ----------------------------
DROP TABLE IF EXISTS `base_attachment`;
CREATE TABLE `base_attachment`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(225) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '上传后文件名',
  `real_name` varchar(225) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件真实名称',
  `path` varchar(225) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件绝对路径',
  `url` varchar(225) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'url相对路径',
  `suff` varchar(225) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件后缀',
  `size` bigint NOT NULL COMMENT '文件大小 B',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态(Y/N)',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1453 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '附件信息表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of base_attachment
-- ----------------------------

-- ----------------------------
-- Table structure for bed
-- ----------------------------
DROP TABLE IF EXISTS `bed`;
CREATE TABLE `bed`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `room_id` bigint NOT NULL COMMENT '房间编号',
  `name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '床位名称',
  `bed_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '床位状态(空闲/预定/入住/退住审核)',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '床位表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of bed
-- ----------------------------

-- ----------------------------
-- Table structure for building
-- ----------------------------
DROP TABLE IF EXISTS `building`;
CREATE TABLE `building`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '楼栋名称',
  `floor_num` int NOT NULL COMMENT '楼层数量',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '楼栋表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of building
-- ----------------------------

-- ----------------------------
-- Table structure for catering_set
-- ----------------------------
DROP TABLE IF EXISTS `catering_set`;
CREATE TABLE `catering_set`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '餐饮套餐名称',
  `month_price` decimal(10, 2) NOT NULL COMMENT '月套餐费用',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '餐饮套餐表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of catering_set
-- ----------------------------

-- ----------------------------
-- Table structure for communication_record
-- ----------------------------
DROP TABLE IF EXISTS `communication_record`;
CREATE TABLE `communication_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `record_date` datetime NOT NULL COMMENT '记录时间',
  `communication_record` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '沟通记录',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '沟通记录表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of communication_record
-- ----------------------------

-- ----------------------------
-- Table structure for consult
-- ----------------------------
DROP TABLE IF EXISTS `consult`;
CREATE TABLE `consult`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `source_id` bigint NOT NULL COMMENT '来源渠道编号',
  `staff_id` bigint NOT NULL COMMENT '接待人编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '咨询人姓名',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '咨询人电话',
  `relation` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '与老人关系',
  `consult_date` datetime NOT NULL COMMENT '咨询日期',
  `consult_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '咨询内容',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 74 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '咨询人表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of consult
-- ----------------------------

-- ----------------------------
-- Table structure for consume
-- ----------------------------
DROP TABLE IF EXISTS `consume`;
CREATE TABLE `consume`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `consume_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消费类别',
  `consume_amount` decimal(10, 2) NOT NULL COMMENT '消费金额',
  `consume_date` datetime NOT NULL COMMENT '消费日期',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消费记录表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of consume
-- ----------------------------

-- ----------------------------
-- Table structure for contract
-- ----------------------------
DROP TABLE IF EXISTS `contract`;
CREATE TABLE `contract`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NOT NULL COMMENT '销售人员编号',
  `sign_date` datetime NOT NULL COMMENT '合同签订日期',
  `start_date` datetime NOT NULL COMMENT '合同开始日期',
  `end_date` datetime NOT NULL COMMENT '合同结束日期',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 62 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '合同表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of contract
-- ----------------------------

-- ----------------------------
-- Table structure for deposit_info
-- ----------------------------
DROP TABLE IF EXISTS `deposit_info`;
CREATE TABLE `deposit_info`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `deposit_id` bigint NOT NULL COMMENT '药品缴存编号',
  `medicine_id` bigint NOT NULL COMMENT '药品编号',
  `deposit_num` int NOT NULL COMMENT '缴存数量',
  `surplus_num` int NOT NULL COMMENT '剩余数量',
  `warn_num` int NOT NULL COMMENT '预警数量',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '缴存药品信息表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of deposit_info
-- ----------------------------

-- ----------------------------
-- Table structure for dishes
-- ----------------------------
DROP TABLE IF EXISTS `dishes`;
CREATE TABLE `dishes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '菜品类别编号',
  `name` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜品名称',
  `price` decimal(10, 2) NOT NULL COMMENT '菜品价格',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜品表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of dishes
-- ----------------------------

-- ----------------------------
-- Table structure for dishes_type
-- ----------------------------
DROP TABLE IF EXISTS `dishes_type`;
CREATE TABLE `dishes_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜品类别名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜品分类表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of dishes_type
-- ----------------------------

-- ----------------------------
-- Table structure for drug_deposit
-- ----------------------------
DROP TABLE IF EXISTS `drug_deposit`;
CREATE TABLE `drug_deposit`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `mode` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '缴存药品方式',
  `deposit_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '缴存状态',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '药品缴存表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of drug_deposit
-- ----------------------------

-- ----------------------------
-- Table structure for elder
-- ----------------------------
DROP TABLE IF EXISTS `elder`;
CREATE TABLE `elder`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `nursing_grade_id` bigint NULL DEFAULT NULL COMMENT '护理等级编号',
  `catering_set_id` bigint NULL DEFAULT NULL COMMENT '餐饮套餐编号',
  `bed_id` bigint NULL DEFAULT NULL COMMENT '床位编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '老人姓名',
  `id_num` varchar(18) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '身份证号',
  `age` int NOT NULL COMMENT '年龄',
  `sex` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '性别(男/女)',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '老人电话',
  `address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '地址',
  `balance` decimal(10, 2) NOT NULL COMMENT '余额',
  `check_flag` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '入住状态',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 90 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '老人表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of elder
-- ----------------------------

-- ----------------------------
-- Table structure for elder_label
-- ----------------------------
DROP TABLE IF EXISTS `elder_label`;
CREATE TABLE `elder_label`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `label_id` bigint NOT NULL COMMENT '标签编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '老人标签表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of elder_label
-- ----------------------------

-- ----------------------------
-- Table structure for emergency_contact
-- ----------------------------
DROP TABLE IF EXISTS `emergency_contact`;
CREATE TABLE `emergency_contact`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '紧急联系人姓名',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '紧急联系人电话',
  `email` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '紧急联系人邮箱',
  `relation` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '与老人关系',
  `receive_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '是否接收消息（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 103 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '紧急联系人表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of emergency_contact
-- ----------------------------

-- ----------------------------
-- Table structure for family_member
-- ----------------------------
DROP TABLE IF EXISTS `family_member`;
CREATE TABLE `family_member`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '家属姓名',
  `id_num` varchar(18) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '身份证号',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '家属电话',
  `email` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '家属邮箱',
  `address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '地址',
  `relation` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '与老人关系',
  `receive_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '是否接收消息（Y/N）',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '老人家属表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of family_member
-- ----------------------------

-- ----------------------------
-- Table structure for floor
-- ----------------------------
DROP TABLE IF EXISTS `floor`;
CREATE TABLE `floor`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `building_id` bigint NOT NULL COMMENT '楼栋编号',
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '楼层名称',
  `room_num` int NOT NULL COMMENT '房间数量',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '楼层表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of floor
-- ----------------------------

-- ----------------------------
-- Table structure for health_data
-- ----------------------------
DROP TABLE IF EXISTS `health_data`;
CREATE TABLE `health_data`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `height` int NOT NULL COMMENT '身高',
  `weight` double NOT NULL COMMENT '体重',
  `temperature` double NOT NULL COMMENT '体温',
  `heart_rate` int NOT NULL COMMENT '心率',
  `systolic_blood_pressure` int NOT NULL COMMENT '收缩血压',
  `diastolic_blood_pressure` int NOT NULL COMMENT '舒张血压',
  `fasting_blood_glucose` int NOT NULL COMMENT '空腹血糖',
  `postprandial_blood_glucose` int NOT NULL COMMENT '餐后血糖',
  `blood_oxygen_saturation` int NOT NULL COMMENT '血氧饱和度',
  `cholesterol` int NOT NULL COMMENT '总胆固醇',
  `uric_acid` int NOT NULL COMMENT '尿酸',
  `left_eye` double NOT NULL COMMENT '左眼',
  `right_eye` double NOT NULL COMMENT '右眼',
  `left_ear` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '左耳',
  `right_ear` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '右耳',
  `muscle_percentage` int NOT NULL COMMENT '肌肉率',
  `body_fat_percentage` int NOT NULL COMMENT '体脂率',
  `waist_circumference` int NOT NULL COMMENT '腰围',
  `hip_circumference` int NOT NULL COMMENT '臀围',
  `moisture_content` int NOT NULL COMMENT '水分率',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '老人健康数据表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of health_data
-- ----------------------------

-- ----------------------------
-- Table structure for health_info
-- ----------------------------
DROP TABLE IF EXISTS `health_info`;
CREATE TABLE `health_info`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `self_care` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '自理情况',
  `vision` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '视力',
  `hearing` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '听力',
  `hospital` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '主治医院',
  `doctor` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '主治医师',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '医院电话',
  `allergy_drug` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '过敏药物',
  `medical_history` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '病史',
  `major_disease` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '主要疾病',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '老人健康信息表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of health_info
-- ----------------------------

-- ----------------------------
-- Table structure for label
-- ----------------------------
DROP TABLE IF EXISTS `label`;
CREATE TABLE `label`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '类别编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签名称',
  `color` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签颜色',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标签表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of label
-- ----------------------------

-- ----------------------------
-- Table structure for label_type
-- ----------------------------
DROP TABLE IF EXISTS `label_type`;
CREATE TABLE `label_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分类名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标签类别表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of label_type
-- ----------------------------

-- ----------------------------
-- Table structure for material
-- ----------------------------
DROP TABLE IF EXISTS `material`;
CREATE TABLE `material`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '物资类别编号',
  `name` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '物资名称',
  `price` decimal(10, 2) NOT NULL COMMENT '物资单价',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '物资表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of material
-- ----------------------------

-- ----------------------------
-- Table structure for material_type
-- ----------------------------
DROP TABLE IF EXISTS `material_type`;
CREATE TABLE `material_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '物资类别名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '物资类别表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of material_type
-- ----------------------------

-- ----------------------------
-- Table structure for medicine
-- ----------------------------
DROP TABLE IF EXISTS `medicine`;
CREATE TABLE `medicine`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '药品名称',
  `type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '药品类别',
  `specification` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '药品规格',
  `dosage_form` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '药品剂型',
  `manufacturer` varchar(25) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '生产厂家',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '药品表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of medicine
-- ----------------------------

-- ----------------------------
-- Table structure for medicine_record
-- ----------------------------
DROP TABLE IF EXISTS `medicine_record`;
CREATE TABLE `medicine_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `deposit_info_id` bigint NOT NULL COMMENT '药品缴存信息编号',
  `medicine_time` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用药时间（早/中/晚）',
  `medicine_date` datetime NOT NULL COMMENT '用药日期',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用药登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of medicine_record
-- ----------------------------

-- ----------------------------
-- Table structure for medicine_set
-- ----------------------------
DROP TABLE IF EXISTS `medicine_set`;
CREATE TABLE `medicine_set`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `deposit_info_id` bigint NOT NULL COMMENT '药品缴存信息编号',
  `medicine_time` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用药时间（餐前/餐后）',
  `day_frequency` int NOT NULL COMMENT '天频率',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用药设置表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of medicine_set
-- ----------------------------

-- ----------------------------
-- Table structure for nurse
-- ----------------------------
DROP TABLE IF EXISTS `nurse`;
CREATE TABLE `nurse`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NOT NULL COMMENT '护理人员编号',
  `nurse_date` datetime NOT NULL COMMENT '护理时间',
  `complete_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '护理完成情况',
  `dine_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '进餐情况',
  `rest` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '休息',
  `take_medicine` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服药',
  `active` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '活动',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护理记录表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse
-- ----------------------------

-- ----------------------------
-- Table structure for nurse_grade
-- ----------------------------
DROP TABLE IF EXISTS `nurse_grade`;
CREATE TABLE `nurse_grade`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '级别名称',
  `type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '护理类型',
  `month_price` decimal(10, 2) NOT NULL COMMENT '月护理费用',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护理等级表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse_grade
-- ----------------------------

-- ----------------------------
-- Table structure for nurse_group
-- ----------------------------
DROP TABLE IF EXISTS `nurse_group`;
CREATE TABLE `nurse_group`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `staff_id` bigint NOT NULL COMMENT '护工小组组长编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '护工小组名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护工小组表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse_group
-- ----------------------------

-- ----------------------------
-- Table structure for nurse_group_member
-- ----------------------------
DROP TABLE IF EXISTS `nurse_group_member`;
CREATE TABLE `nurse_group_member`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `group_id` bigint NOT NULL COMMENT '护工小组编号',
  `staff_id` bigint NOT NULL COMMENT '护工编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护工小组成员表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse_group_member
-- ----------------------------

-- ----------------------------
-- Table structure for nurse_item
-- ----------------------------
DROP TABLE IF EXISTS `nurse_item`;
CREATE TABLE `nurse_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `grade_id` bigint NOT NULL COMMENT '护理等级编号',
  `service_id` bigint NOT NULL COMMENT '服务项目编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 54 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护理项目表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse_item
-- ----------------------------

-- ----------------------------
-- Table structure for nurse_reserve
-- ----------------------------
DROP TABLE IF EXISTS `nurse_reserve`;
CREATE TABLE `nurse_reserve`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NULL DEFAULT NULL COMMENT '服务人编号',
  `service_name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务项目名称',
  `need_date` int NOT NULL COMMENT '所需时间',
  `service_price` decimal(10, 2) NOT NULL COMMENT '服务费用',
  `charge_method` varchar(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收费方式',
  `frequency` int NOT NULL COMMENT '服务次数',
  `pay_amount` decimal(10, 2) NOT NULL COMMENT '支付总额',
  `order_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单状态',
  `nurse_date` datetime NULL DEFAULT NULL COMMENT '护理时间',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '护理预定表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of nurse_reserve
-- ----------------------------

-- ----------------------------
-- Table structure for order
-- ----------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NULL DEFAULT NULL COMMENT '送餐人编号',
  `deliver_dishes_date` datetime NULL DEFAULT NULL COMMENT '送餐时间',
  `dine_date` datetime NOT NULL COMMENT '就餐时间',
  `dine_type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '就餐方式',
  `pay_amount` decimal(10, 2) NOT NULL COMMENT '支付总额',
  `order_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单状态',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订餐表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of order
-- ----------------------------

-- ----------------------------
-- Table structure for order_dishes
-- ----------------------------
DROP TABLE IF EXISTS `order_dishes`;
CREATE TABLE `order_dishes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `order_id` bigint NOT NULL COMMENT '订餐编号',
  `dishes_name` varchar(15) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜品名称',
  `dishes_price` decimal(10, 2) NOT NULL COMMENT '菜品价格',
  `order_num` int NOT NULL COMMENT '菜品份数',
  `set_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '套餐标记',
  `total_amount` decimal(10, 2) NOT NULL COMMENT '菜品总额',
  `really_amount` decimal(10, 2) NOT NULL COMMENT '实际总额',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订餐食物表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of order_dishes
-- ----------------------------

-- ----------------------------
-- Table structure for outbound_material
-- ----------------------------
DROP TABLE IF EXISTS `outbound_material`;
CREATE TABLE `outbound_material`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `outbound_record_id` bigint NOT NULL COMMENT '出库登记编号',
  `warehouse_material_id` bigint NOT NULL COMMENT '入库物资编号',
  `material_id` bigint NOT NULL COMMENT '物资编号',
  `outbound_num` int NOT NULL COMMENT '出库数量',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '出库物资表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of outbound_material
-- ----------------------------

-- ----------------------------
-- Table structure for outbound_record
-- ----------------------------
DROP TABLE IF EXISTS `outbound_record`;
CREATE TABLE `outbound_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `warehouse_id` bigint NOT NULL COMMENT '仓库编号',
  `staff_id` bigint NOT NULL COMMENT '经办人编号',
  `recipient_id` bigint NOT NULL COMMENT '领用人编号',
  `recipient_type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '领用人类型',
  `material_use` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '物资去向',
  `outbound_date` datetime NOT NULL COMMENT '出库时间',
  `outbound_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '出库状态',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '出库登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of outbound_record
-- ----------------------------

-- ----------------------------
-- Table structure for outward
-- ----------------------------
DROP TABLE IF EXISTS `outward`;
CREATE TABLE `outward`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `chaperone_name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '陪同人姓名',
  `chaperone_phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '陪同人电话',
  `chaperone_type` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '陪同人类型（家属/护工）',
  `outward_date` datetime NOT NULL COMMENT '外出时间',
  `plan_return_date` datetime NOT NULL COMMENT '计划返回时间',
  `real_return_date` datetime NULL DEFAULT NULL COMMENT '实际返回时间',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '外出登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of outward
-- ----------------------------

-- ----------------------------
-- Table structure for reserve
-- ----------------------------
DROP TABLE IF EXISTS `reserve`;
CREATE TABLE `reserve`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `staff_id` bigint NOT NULL COMMENT '销售人员编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '交款人姓名',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '交款人电话',
  `due_date` datetime NOT NULL COMMENT '预定到期时间',
  `deposit` decimal(10, 2) NOT NULL COMMENT '定金',
  `reserve_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '退款状态（N/Y）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 30 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '预定表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of reserve
-- ----------------------------

-- ----------------------------
-- Table structure for retreat
-- ----------------------------
DROP TABLE IF EXISTS `retreat`;
CREATE TABLE `retreat`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `retreat_form` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '退住形式',
  `evaluate` int NOT NULL COMMENT '对老人评价',
  `retreat_cause` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '退住原因',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '退住登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of retreat
-- ----------------------------

-- ----------------------------
-- Table structure for retreat_apply
-- ----------------------------
DROP TABLE IF EXISTS `retreat_apply`;
CREATE TABLE `retreat_apply`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `apply_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '申请状态',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 13 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '退住申请表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of retreat_apply
-- ----------------------------

-- ----------------------------
-- Table structure for role
-- ----------------------------
DROP TABLE IF EXISTS `role`;
CREATE TABLE `role`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of role
-- ----------------------------

-- ----------------------------
-- Table structure for role_auth
-- ----------------------------
DROP TABLE IF EXISTS `role_auth`;
CREATE TABLE `role_auth`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `role_id` bigint NOT NULL COMMENT '角色编号',
  `auth_id` bigint NOT NULL COMMENT '权限编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 83 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色权限表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of role_auth
-- ----------------------------

-- ----------------------------
-- Table structure for role_copy1
-- ----------------------------
DROP TABLE IF EXISTS `role_copy1`;
CREATE TABLE `role_copy1`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of role_copy1
-- ----------------------------

-- ----------------------------
-- Table structure for room
-- ----------------------------
DROP TABLE IF EXISTS `room`;
CREATE TABLE `room`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '房间类型编号',
  `floor_id` bigint NOT NULL COMMENT '楼层编号',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '房间名称',
  `bed_num` int NOT NULL COMMENT '床位数量',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '房间表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of room
-- ----------------------------

-- ----------------------------
-- Table structure for room_type
-- ----------------------------
DROP TABLE IF EXISTS `room_type`;
CREATE TABLE `room_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '房间类型名称',
  `month_price` decimal(10, 2) NOT NULL COMMENT '月房间费用',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '房间类型表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of room_type
-- ----------------------------

-- ----------------------------
-- Table structure for service_item
-- ----------------------------
DROP TABLE IF EXISTS `service_item`;
CREATE TABLE `service_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `type_id` bigint NOT NULL COMMENT '服务项目类别编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务名称',
  `charge_method` varchar(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收费方式',
  `price` decimal(10, 2) NOT NULL COMMENT '服务费用',
  `need_date` int NOT NULL COMMENT '所需时间(分)',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 19 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务项目表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of service_item
-- ----------------------------

-- ----------------------------
-- Table structure for service_type
-- ----------------------------
DROP TABLE IF EXISTS `service_type`;
CREATE TABLE `service_type`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务项目名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '服务项目分类表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of service_type
-- ----------------------------

-- ----------------------------
-- Table structure for set_dishes
-- ----------------------------
DROP TABLE IF EXISTS `set_dishes`;
CREATE TABLE `set_dishes`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `set_id` bigint NOT NULL COMMENT '餐饮套餐编号',
  `dishes_id` bigint NOT NULL COMMENT '菜品食物编号',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '套餐食物表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of set_dishes
-- ----------------------------

-- ----------------------------
-- Table structure for source
-- ----------------------------
DROP TABLE IF EXISTS `source`;
CREATE TABLE `source`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来源渠道名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '来源渠道表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of source
-- ----------------------------

-- ----------------------------
-- Table structure for staff
-- ----------------------------
DROP TABLE IF EXISTS `staff`;
CREATE TABLE `staff`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `role_id` bigint NOT NULL COMMENT '角色编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '姓名',
  `id_num` varchar(18) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '身份证号',
  `age` int NOT NULL COMMENT '年龄',
  `sex` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '性别(男/女)',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '电话',
  `email` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '邮箱',
  `pass` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `avator` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '头像',
  `address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '地址',
  `leave_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '离职状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '员工表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of staff
-- ----------------------------

-- ----------------------------
-- Table structure for visit
-- ----------------------------
DROP TABLE IF EXISTS `visit`;
CREATE TABLE `visit`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来访者姓名',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来访者电话',
  `relation` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '与老人关系',
  `visit_date` datetime NOT NULL COMMENT '来访时间',
  `leave_date` datetime NULL DEFAULT NULL COMMENT '离开时间',
  `visit_num` int NOT NULL COMMENT '来访者人数',
  `visit_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '来访状态',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '来访登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of visit
-- ----------------------------

-- ----------------------------
-- Table structure for visit_plan
-- ----------------------------
DROP TABLE IF EXISTS `visit_plan`;
CREATE TABLE `visit_plan`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `elder_id` bigint NOT NULL COMMENT '老人编号',
  `title` varchar(25) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '回访计划标题',
  `plan_date` datetime NOT NULL COMMENT '计划回访时间',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '回访计划内容',
  `complete_date` datetime NULL DEFAULT NULL COMMENT '计划完成时间',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '回访计划表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of visit_plan
-- ----------------------------

-- ----------------------------
-- Table structure for warehouse
-- ----------------------------
DROP TABLE IF EXISTS `warehouse`;
CREATE TABLE `warehouse`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `staff_id` bigint NOT NULL COMMENT '仓库管理员编号',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '仓库名称',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '仓库表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of warehouse
-- ----------------------------

-- ----------------------------
-- Table structure for warehouse_material
-- ----------------------------
DROP TABLE IF EXISTS `warehouse_material`;
CREATE TABLE `warehouse_material`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `warehouse_record_id` bigint NOT NULL COMMENT '入库登记编号',
  `material_id` bigint NOT NULL COMMENT '物资编号',
  `warehouse_num` int NOT NULL COMMENT '入库数量',
  `inventory` int NOT NULL COMMENT '库存量',
  `product_date` datetime NOT NULL COMMENT '生产日期',
  `expire_date` datetime NOT NULL COMMENT '有效期',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '入库物资表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of warehouse_material
-- ----------------------------

-- ----------------------------
-- Table structure for warehouse_record
-- ----------------------------
DROP TABLE IF EXISTS `warehouse_record`;
CREATE TABLE `warehouse_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `warehouse_id` bigint NOT NULL COMMENT '仓库编号',
  `staff_id` bigint NOT NULL COMMENT '经办人编号',
  `source` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '物资来源',
  `warehouse_date` datetime NOT NULL COMMENT '入库时间',
  `warehouse_flag` varchar(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '入库状态',
  `del_flag` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '删除状态（Y/N）',
  `create_id` bigint NOT NULL COMMENT '创建人编号',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_id` bigint NOT NULL COMMENT '修改人编号',
  `update_time` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '入库登记表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of warehouse_record
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
