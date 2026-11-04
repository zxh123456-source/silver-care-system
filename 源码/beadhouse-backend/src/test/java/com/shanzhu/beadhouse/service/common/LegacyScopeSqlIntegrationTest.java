package com.shanzhu.beadhouse.service.common;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.shanzhu.beadhouse.dao.mapper.*;
import com.shanzhu.beadhouse.entity.query.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Dedicated throwaway MySQL loaded with the demo dump; all fixture writes roll back. */
@EnabledIfEnvironmentVariable(named="SCOPE_TEST_DB_URL", matches=".+")
class LegacyScopeSqlIntegrationTest {
    @Test void everyListFiltersInSqlAndRevocationAppliesImmediately() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("SCOPE_TEST_DB_URL"), "root", System.getenv("SCOPE_TEST_DB_PASSWORD"));
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds); bean.setConfiguration(new MybatisConfiguration());
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        try (SqlSession session = bean.getObject().openSession(false)) {
            try {
                Connection conn = session.getConnection();
                try (Statement st = conn.createStatement()) {
                    // This suite verifies assignment-only care stages. Historical sales links in
                    // the demo dump must not turn unrelated pre-admission rows into allowed rows.
                    // Invalidated demo identities may intentionally duplicate a live identity.
                    st.executeUpdate("UPDATE elder SET check_flag='入住' WHERE check_flag<>'已失效'");
                    st.executeUpdate("UPDATE elder SET name='allowed',check_flag='退住审核' WHERE id=4");
                    st.executeUpdate("UPDATE elder SET name='foreign',check_flag='退住审核' WHERE id=8");
                    st.executeUpdate("DELETE FROM elder_staff_assignment");
                    st.executeUpdate("INSERT INTO elder_staff_assignment(elder_id,staff_id,active) VALUES(4,4,'Y')");
                    for (String table : Arrays.asList("accident","outward","nurse_reserve","order","consume")) {
                        cloneFirstRow(conn,table);
                        st.executeUpdate("UPDATE `"+table+"` SET elder_id=4");
                        st.executeUpdate("UPDATE `"+table+"` SET elder_id=8 ORDER BY id LIMIT 1");
                    }
                    // One real pending application per resident, rather than duplicate pending rows.
                    // This entire fixture rolls back with the surrounding SQL session.
                    st.executeUpdate("DELETE FROM retreat_apply");
                    st.executeUpdate("INSERT INTO retreat_apply(elder_id,apply_flag,create_id,create_time,update_id,update_time) "
                            + "VALUES(4,'待审核',1,NOW(),1,NOW()),(8,'待审核',1,NOW(),1,NOW())");
                    st.executeUpdate("UPDATE accident SET del_flag='N'");
                    st.executeUpdate("UPDATE outward SET del_flag='N'");
                }
                for (Long staff : Arrays.asList(null,4L,5L)) {
                    List<List<?>> lists = queryAll(session,staff);
                    for (List<?> list : lists) {
                        if (staff == null) assertThat(list.size()).isGreaterThan(1);
                        else if (staff == 5L) assertThat(list).isEmpty();
                        else {
                            assertThat(list).isNotEmpty();
                            for (Object row : list) {
                                String name;
                                try { name = (String) row.getClass().getMethod("getElderName").invoke(row); }
                                catch (NoSuchMethodException ignored) { name = (String) row.getClass().getMethod("getName").invoke(row); }
                                assertThat(name).isEqualTo("allowed");
                            }
                        }
                    }
                }
                try(Statement st=conn.createStatement()) { st.executeUpdate("UPDATE elder_staff_assignment SET active='N' WHERE staff_id=4"); }
                session.clearCache();
                for(List<?> list:queryAll(session,4L)) assertThat(list).isEmpty();
            } finally { session.rollback(); }
        }
    }
    private List<List<?>> queryAll(SqlSession s, Long staff) {
        ElderMapper elders=s.getMapper(ElderMapper.class);
        return Arrays.asList(
            elders.listElderByKey(new PageElderByKeyQuery(),staff),
            elders.listDepositRechargeByKey(new PageDepositRechargeByKeyQuery(),staff),
            elders.listScopedElders(null,null,Arrays.asList("入住","退住审核"),staff),
            s.getMapper(AccidentMapper.class).listAccidentByKeyVo(new PageAccidentByKeyQuery(),staff),
            s.getMapper(OutwardMapper.class).listOutwardByKey(new PageOutwardByKeyQuery(),null,null,staff),
            s.getMapper(NurseReserveMapper.class).listNurseReserveByKey(new PageNurseReserveByKeyQuery(),staff),
            s.getMapper(OrderMapper.class).listOrderByKey(new PageOrderByKeyQuery(),staff),
            s.getMapper(ConsumeMapper.class).listConsumeByKey(null,null,null,staff),
            s.getMapper(RetreatApplyMapper.class).listRetreatApplyByKey(new PageRetreatApplyQuery(),staff),
            s.getMapper(RetreatApplyMapper.class).listRetreatAuditByKey(new PageRetreatAuditQuery(),staff));
    }
    private void cloneFirstRow(Connection conn,String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs=conn.getMetaData().getColumns(null,null,table,null)) {
            while(rs.next()) if(!"id".equals(rs.getString("COLUMN_NAME")) && !"YES".equals(rs.getString("IS_GENERATEDCOLUMN")))
                columns.add("`"+rs.getString("COLUMN_NAME")+"`");
        }
        String cols=String.join(",",columns);
        try(Statement st=conn.createStatement()) {
            int inserted=st.executeUpdate("INSERT INTO `"+table+"` ("+cols+") SELECT "+cols+" FROM `"+table+"` LIMIT 1");
            assertThat(inserted).as("demo fixture exists for "+table).isEqualTo(1);
        }
    }
}
