package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.AuthResultStatusAdvice;
import com.shanzhu.beadhouse.entity.base.Result;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthResponseStatusTest {
    @RestController static class Responses {
        @GetMapping("/response/{code}") public Result response(@PathVariable int code) { return Result.error(code,"fixture"); }
    }
    @Test void returnedAuthErrorsUseRealStatusAndOtherBusinessCodesRemainCompatible() throws Exception {
        MockMvc http=MockMvcBuilders.standaloneSetup(new Responses()).setControllerAdvice(new AuthResultStatusAdvice()).build();
        for(int code:new int[]{401,403,503}) http.perform(get("/response/"+code)).andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));
        http.perform(get("/response/409")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(409));
    }
}
