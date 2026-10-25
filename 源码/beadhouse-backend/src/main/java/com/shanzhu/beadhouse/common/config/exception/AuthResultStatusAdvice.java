package com.shanzhu.beadhouse.common.config.exception;

import com.shanzhu.beadhouse.common.util.ResponseUtil;
import com.shanzhu.beadhouse.entity.base.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Apply the same authentication status to returned Results and exception/filter responses. */
@RestControllerAdvice
public class AuthResultStatusAdvice implements ResponseBodyAdvice<Result> {
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return Result.class.isAssignableFrom(method.getParameterType());
    }
    @Override public Result beforeBodyWrite(Result body, MethodParameter method, MediaType media,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body != null) {
            int status = ResponseUtil.httpStatus(body.getCode());
            if (status != 200) response.setStatusCode(HttpStatus.valueOf(status));
        }
        return body;
    }
}
