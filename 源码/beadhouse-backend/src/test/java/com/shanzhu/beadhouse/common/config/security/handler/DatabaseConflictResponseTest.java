package com.shanzhu.beadhouse.common.config.security.handler;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

class DatabaseConflictResponseTest {
    @RestController static class FixtureController {
        @GetMapping("/fixture-conflict") String conflict() {
            throw new DuplicateKeyException("SQL private_identity_value unique constraint internal_table");
        }
    }
    @Test void duplicateIdentityReturnsAnExplicitConflictWithoutDatabaseDetails() throws Exception {
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new BusinessRuntimeExceptionAdvice()).build();
        mvc.perform(get("/fixture-conflict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value(not(containsString("private_identity_value"))))
                .andExpect(jsonPath("$.msg").value(not(containsString("internal_table"))));
    }
}
