package com.shanzhu.beadhouse.common.util;

import cn.hutool.json.JSONUtil;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.entity.base.Result;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
public class ResponseUtil {
    public static void resultOut(HttpServletResponse response, Result result) {
        String errorJson = JSONUtil.toJsonStr(result);
        out(response, errorJson, result.getCode());
    }

    public static void exceptionEnumOut(HttpServletResponse response, ExceptionEnum exceptionEnum) {
        String errorJson = JSONUtil.toJsonStr(Result.error(exceptionEnum));
        out(response, errorJson, exceptionEnum.getCode());
    }

    public static int httpStatus(Integer code) {
        return code != null && (code == 401 || code == 403 || code == 503) ? code : 200;
    }
    private static void out(HttpServletResponse response, String errorJson, Integer code) {
        // Preserve legacy business-result transport while making authentication/availability explicit.
        response.setStatus(httpStatus(code));
        response.setContentType("application/json;charset=UTF-8");
        response.addHeader("Content-Type", "application/json;charset=utf-8");
        try {
            response.getWriter().write(errorJson);
            response.flushBuffer();
        } catch (IOException e) {
            log.warn("Write data failed.", e);
        }
    }
}
