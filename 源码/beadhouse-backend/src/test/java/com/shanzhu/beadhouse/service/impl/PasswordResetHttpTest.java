package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.controller.AccountController;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordResetHttpTest {
    @Test void sendCodeAcceptsJsonPostAndRejectsOldGet() throws Exception {
        AccountController controller=new AccountController(); AccountService service=mock(AccountService.class);
        ReflectionTestUtils.setField(controller,"accountService",service);
        when(service.sendCode(any())).thenReturn(Result.success("验证码申请已受理"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/account/sendCode").contentType("application/json").content("{\"account\":\"demo@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/account/sendCode").param("account","demo@example.com"))
                .andExpect(status().isMethodNotAllowed());
        verify(service,times(1)).sendCode(any());
    }
}
