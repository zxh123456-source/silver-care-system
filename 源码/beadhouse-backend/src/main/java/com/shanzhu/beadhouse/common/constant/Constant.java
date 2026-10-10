package com.shanzhu.beadhouse.common.constant;

public interface Constant {
    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    // 项目主题
    String SUBJECT = "养老院管理系统";

    // DEVELOPER
    // 开发者
    String DEVELOPER = "@developer ";
    // 开发者姓名
    String EMPEROR_WEN = "程序员山山";

    // ANONYMOUS
    // 匿名用户
    String ANONYMOUS_USER = "anonymousUser";

    // AES
    //偏移量,AES 为16bytes. DES 为8bytes
    String IV = env("LEGACY_AES_IV", "");
    //私钥,AES固定格式为128/192/256 bits.即：16/24/32bytes。DES固定格式为64bits，即8bytes。
    String AES_KEY = env("LEGACY_AES_KEY", "");
    //填充类型，DES加密把前面的AES改成DES即可
    String AES_TYPE = "AES/CBC/PKCS5Padding";

    // JWT
    // 主题
    String TOKEN_SUBJECT = SUBJECT;
    // 发行人
    String TOKEN_ISSURE = "程序员山山";
    // 签名哈希
    String TOKEN_SECRET = env("JWT_SECRET", "");

    // EMAIL
    // 邮箱地址
    String MAIL_HOST = env("MAIL_HOST", "");
    // 企业邮箱
    String MAIL = env("MAIL_ADDRESS", "");
    // 企业邮箱
    String PASS = env("MAIL_PASSWORD", "");

    // EXPIRE
    // jwt/redis过期时间
    long EXPIRE_TIME = 1000 * 60 * 60 * 24;

    // REGULAR
    // 邮箱正则
    String EMAIL_REGULAR = "[a-zA-Z0-9]+@[a-zA-Z0-9]+\\.[a-zA-Z0-9]+";
    // 手机号正则
    //    String PHONE_REGULAR = "/^1(3d|4[5-9]|5[0-35-9]|6[567]|7[0-8]|8d|9[0-35-9])d{8}$/";
    String PHONE_REGULAR = "^1[35789]\\d{9}$";

    // REDIS
    // 登录存入redis前缀
    String LOGIN_REDIS = "login:";

    // FILE
    // 文件
    String STR_DOWNLOAD = "download";
    String STR_UPLOAD_IMG = "upload/img";
    String STR_UPLOAD_FILE = "upload/file";
    String STR_UPLOAD_VIDEO = "upload/video";

    // OTHER
    // 标签总数限制
    Long TOTAL_LIMIT = 10L;
    // 符号
    String COMMA = ",";
}
