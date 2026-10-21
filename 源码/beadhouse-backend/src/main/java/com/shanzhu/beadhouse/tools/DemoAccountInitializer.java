package com.shanzhu.beadhouse.tools;

import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import redis.clients.jedis.Jedis;

import java.sql.*;

/** Explicit local-demo bootstrap, independent of Spring startup and legacy AES keys. */
public final class DemoAccountInitializer {
    private static final String SEED_PASSWORD = "7217ac017b4fb2352ec9e65576c5c0b1";
    private DemoAccountInitializer() { }

    public static void main(String[] args) {
        if (args.length != 1 || !"--confirm-demo".equals(args[0])) {
            System.err.println("Refusing: pass --confirm-demo only for the disposable demo database.");
            System.exit(2);
        }
        try (Connection db = DriverManager.getConnection(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"));
             Jedis redis = new Jedis(env("SPRING_REDIS_HOST", "127.0.0.1"), Integer.parseInt(env("SPRING_REDIS_PORT", "6379")), 5000)) {
            redis.auth(required("REDIS_PASSWORD"));
            redis.select(Integer.parseInt(env("SPRING_REDIS_DATABASE", "1")));
            redis.ping();
            boolean changed = initialize(db, required("DEMO_ACCOUNT_PASSWORD"), () ->
                    redis.del(new JdkSerializationRedisSerializer().serialize("login:1")));
            System.out.println(changed ? "DEMO_INITIALIZED" : "DEMO_UNCHANGED");
        } catch (Exception failure) {
            // Do not expose JDBC URLs, credentials, or database contents in CLI diagnostics.
            System.err.println("Demo initialization failed (" + failure.getClass().getSimpleName()
                    + "). Check database/Redis access and the original seed administrator; no credentials printed.");
            System.exit(1);
        }
    }

    static boolean initialize(Connection db, String password, Runnable revokeSession) throws SQLException {
        if (password == null || password.length() < 16 || password.length() > 64) {
            throw new IllegalArgumentException("Demo password must contain 16 to 64 characters");
        }
        db.setAutoCommit(false);
        try {
            // Exact seed identity + original cipher: never reset a changed password or another administrator.
            try (PreparedStatement query = db.prepareStatement("SELECT role_id,name,phone,pass,leave_flag FROM staff WHERE id=1 FOR UPDATE");
                 ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getInt("role_id") != 1 || !"超管".equals(row.getString("name"))
                        || !"13547584400".equals(row.getString("phone")) || !"N".equals(row.getString("leave_flag"))) {
                    throw new IllegalStateException("Not the original demo administrator");
                }
                if (!SEED_PASSWORD.equals(row.getString("pass"))) {
                    db.rollback();
                    return false;
                }
            }
            try (PreparedStatement update = db.prepareStatement("UPDATE staff SET pass=? WHERE id=1 AND pass=?")) {
                update.setString(1, new BCryptPasswordEncoder().encode(password));
                update.setString(2, SEED_PASSWORD);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Demo administrator changed concurrently");
            }
            revokeSession.run();
            db.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
