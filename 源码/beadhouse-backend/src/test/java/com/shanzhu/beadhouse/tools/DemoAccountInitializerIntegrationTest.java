package com.shanzhu.beadhouse.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_TEST_DB_URL", matches=".+")
class DemoAccountInitializerIntegrationTest {
    @Test void onlyOriginalSeedIsChangedAndRepetitionIsSafe() throws Exception {
        try (Connection db = fixture()) {
            AtomicInteger revoked = new AtomicInteger();
            assertThat(DemoAccountInitializer.initialize(db, "local-demo-test-password", revoked::incrementAndGet)).isTrue();
            String saved = password(db);
            assertThat(new BCryptPasswordEncoder().matches("local-demo-test-password", saved)).isTrue();
            assertThat(DemoAccountInitializer.initialize(db, "another-test-password", revoked::incrementAndGet)).isFalse();
            assertThat(password(db)).isEqualTo(saved);
            assertThat(revoked.get()).isEqualTo(1);
        }
    }
    @Test void wrongIdentityAndShortPasswordCannotResetAccount() throws Exception {
        try (Connection db = fixture()) {
            String before = password(db);
            assertThatThrownBy(() -> DemoAccountInitializer.initialize(db, "short", () -> {})).isInstanceOf(IllegalArgumentException.class);
            db.createStatement().executeUpdate("UPDATE staff SET name='other'");
            assertThatThrownBy(() -> DemoAccountInitializer.initialize(db, "local-demo-test-password", () -> {})).isInstanceOf(IllegalStateException.class);
            assertThat(password(db)).isEqualTo(before);
        }
    }
    @Test void sessionInvalidationFailureRollsBackPassword() throws Exception {
        try (Connection db = fixture()) {
            String before = password(db);
            assertThatThrownBy(() -> DemoAccountInitializer.initialize(db, "local-demo-test-password", () -> { throw new IllegalStateException("Redis unavailable"); }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(password(db)).isEqualTo(before);
        }
    }
    private Connection fixture() throws Exception {
        Connection db = DriverManager.getConnection(System.getenv("SCOPE_TEST_DB_URL"), "root", System.getenv("SCOPE_TEST_DB_PASSWORD"));
        // Clone the imported schema instead of mirroring the initializer's column assumptions.
        // Connection-local tables shadow the real table; tests never change imported staff records.
        db.createStatement().execute("CREATE TEMPORARY TABLE demo_staff_fixture LIKE staff");
        db.createStatement().executeUpdate("INSERT INTO demo_staff_fixture SELECT * FROM staff WHERE id=1");
        db.createStatement().execute("CREATE TEMPORARY TABLE staff LIKE demo_staff_fixture");
        db.createStatement().executeUpdate("INSERT INTO staff SELECT * FROM demo_staff_fixture");
        db.createStatement().executeUpdate("UPDATE staff SET role_id=1,name='超管',phone='13547584400',pass='7217ac017b4fb2352ec9e65576c5c0b1',leave_flag='N' WHERE id=1");
        return db;
    }
    private String password(Connection db) throws Exception {
        try (ResultSet row = db.createStatement().executeQuery("SELECT pass FROM staff WHERE id=1")) { row.next(); return row.getString(1); }
    }
}
