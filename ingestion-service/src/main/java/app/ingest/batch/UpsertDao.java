package app.ingest.batch;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UpsertDao {

    // Existing rows decay their recent_score to now() before adding the new delta; new rows
    // insert at the raw delta. all_time_count simply accumulates. One round-trip per flush.
    private static final String SQL = """
        INSERT INTO queries (query, all_time_count, recent_score, last_updated)
        VALUES (?, ?, ?, now())
        ON CONFLICT (query) DO UPDATE SET
            all_time_count = queries.all_time_count + EXCLUDED.all_time_count,
            recent_score   = queries.recent_score
                               * pow(?, extract(epoch FROM now() - queries.last_updated))
                             + EXCLUDED.recent_score,
            last_updated   = now()
        """;

    private final JdbcTemplate jdbc;

    public UpsertDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void batchUpsert(Map<String, Long> deltas, double perSecondFactor) {
        List<Map.Entry<String, Long>> entries = List.copyOf(deltas.entrySet());
        jdbc.batchUpdate(SQL, new BatchPreparedStatementSetter() {
            @Override
            public int getBatchSize() {
                return entries.size();
            }

            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<String, Long> e = entries.get(i);
                long delta = e.getValue();
                ps.setString(1, e.getKey());
                ps.setLong(2, delta);
                ps.setDouble(3, delta);
                ps.setDouble(4, perSecondFactor);
            }
        });
    }
}
