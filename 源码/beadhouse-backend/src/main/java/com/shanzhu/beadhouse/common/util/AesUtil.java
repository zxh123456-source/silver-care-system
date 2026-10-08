package com.shanzhu.beadhouse.common.util;

import com.shanzhu.beadhouse.common.constant.Constant;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Objects;

/**
 * 自定义密码组件
 */
@Component
public class AesUtil {
    /**
     * 加密
     *
     * @param encodeStr
     * @return
     */
    public static String aesEncode(String encodeStr) {
        try {
            IvParameterSpec ivParameterSpec = new IvParameterSpec(Constant.IV.getBytes());
            SecretKeySpec secretKeySpec = new SecretKeySpec(Constant.AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance(Constant.AES_TYPE);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] bytes = cipher.doFinal(encodeStr.getBytes());
            return new String(Hex.encode(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("历史密码兼容需要有效的 LEGACY_AES_IV 和 LEGACY_AES_KEY", e);
        }
    }

    /**
     * 解密
     *
     * @param decodeStr
     * @return
     */
    public static String aesDecode(String decodeStr) {
        try {
            IvParameterSpec ivParameterSpec = new IvParameterSpec(Constant.IV.getBytes());
            SecretKeySpec secretKeySpec = new SecretKeySpec(Constant.AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance(Constant.AES_TYPE);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] bytes = cipher.doFinal(Hex.decode(decodeStr));
            return new String(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("历史密码解密失败，请检查 LEGACY_AES_IV 和 LEGACY_AES_KEY", e);
        }
    }

    /**
     * 校验
     *
     * @param needCheckPassword
     * @param encodePassword
     * @return
     */
    public static Boolean aesMatch(String needCheckPassword, String encodePassword) {
        return Objects.equals(aesEncode(needCheckPassword), encodePassword);
    }
}
