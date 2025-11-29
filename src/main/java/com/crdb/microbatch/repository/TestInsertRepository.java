package com.crdb.microbatch.repository;

import com.crdb.microbatch.model.TestInsert;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * Repository for test insert operations.
 * 
 * <p>Handles single-row and batch inserts into the test_inserts table.
 */
@Repository
public class TestInsertRepository {

    private static final String INSERT_SQL = """
        INSERT INTO test_inserts (
            id, timestamp_col, varchar_col, text_col, integer_col, bigint_col,
            decimal_col, boolean_col, jsonb_col, array_col, uuid_col,
            date_col, time_col, double_col, float_col, created_at, updated_at
        ) VALUES (
            ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """;
    
    /**
     * Test method to verify a single insert works.
     * 
     * @param testInsert the test insert record
     * @return true if insert succeeded
     */
    public boolean testSingleInsert(TestInsert testInsert) {
        try {
            int rowsAffected = insert(testInsert);
            return rowsAffected == 1;
        } catch (Exception e) {
            System.err.println("Single insert test failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor for TestInsertRepository.
     * 
     * @param jdbcTemplate the JdbcTemplate instance
     */
    public TestInsertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a single test record into the database.
     * 
     * @param testInsert the test insert record to insert
     * @return the number of rows affected (should be 1)
     */
    public int insert(TestInsert testInsert) {
        return jdbcTemplate.update(INSERT_SQL,
            testInsert.id(),
            testInsert.timestampCol(),
            testInsert.varcharCol(),
            testInsert.textCol(),
            testInsert.integerCol(),
            testInsert.bigintCol(),
            testInsert.decimalCol(),
            testInsert.booleanCol(),
            testInsert.jsonbCol(),
            testInsert.arrayCol() != null ? testInsert.arrayCol().toArray(new Integer[0]) : null,
            testInsert.uuidCol(),
            testInsert.dateCol(),
            testInsert.timeCol(),
            testInsert.doubleCol(),
            testInsert.floatCol(),
            testInsert.createdAt(),
            testInsert.updatedAt()
        );
    }

    /**
     * Inserts a batch of test records into the database using JDBC batch update.
     * 
     * <p>Simple implementation using ConnectionCallback - Spring handles transactions automatically.
     * 
     * @param testInserts the list of test insert records to insert
     * @return array of update counts (one per record)
     */
    public int[] insertBatch(List<TestInsert> testInserts) {
        if (testInserts == null || testInserts.isEmpty()) {
            return new int[0];
        }
        
        // Optimized batch insert: reuse PreparedStatement, minimize allocations
        // Spring's JdbcTemplate handles transaction management automatically
        return jdbcTemplate.execute((ConnectionCallback<int[]>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                // Pre-size batch to avoid reallocations
                int size = testInserts.size();
                for (int i = 0; i < size; i++) {
                    setParameters(ps, testInserts.get(i));
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        });
    }
    
    /**
     * Sets parameters on a PreparedStatement for a single TestInsert.
     * 
     * @param ps the prepared statement
     * @param testInsert the test insert record
     */
    private void setParameters(PreparedStatement ps, TestInsert testInsert) throws java.sql.SQLException {
        ps.setObject(1, testInsert.id());
        ps.setObject(2, testInsert.timestampCol());
        ps.setString(3, testInsert.varcharCol());
        ps.setString(4, testInsert.textCol());
        ps.setInt(5, testInsert.integerCol());
        ps.setLong(6, testInsert.bigintCol());
        ps.setBigDecimal(7, testInsert.decimalCol());
        ps.setBoolean(8, testInsert.booleanCol());
        ps.setString(9, testInsert.jsonbCol());
        
        // Handle array column
        if (testInsert.arrayCol() != null && !testInsert.arrayCol().isEmpty()) {
            ps.setArray(10, ps.getConnection().createArrayOf("INTEGER",
                testInsert.arrayCol().toArray(new Integer[0])));
        } else {
            ps.setNull(10, java.sql.Types.ARRAY);
        }
        
        ps.setObject(11, testInsert.uuidCol());
        ps.setObject(12, testInsert.dateCol());
        ps.setObject(13, testInsert.timeCol());
        ps.setDouble(14, testInsert.doubleCol());
        ps.setFloat(15, testInsert.floatCol());
        ps.setObject(16, testInsert.createdAt());
        ps.setObject(17, testInsert.updatedAt());
    }

    /**
     * Gets the total count of inserted records.
     * 
     * @return the total count
     */
    public long getCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_inserts", Long.class);
        return count != null ? count : 0L;
    }
    
    /**
     * Truncates the test_inserts table (faster than DELETE).
     * 
     * @return number of rows affected (should be 0 for TRUNCATE)
     */
    public void truncateTable() {
        jdbcTemplate.execute("TRUNCATE TABLE test_inserts");
    }
    
    /**
     * Drops and recreates the test_inserts table.
     * 
     * <p>This is faster than TRUNCATE for very large tables.
     */
    public void dropAndRecreateTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS test_inserts CASCADE");
        jdbcTemplate.execute("""
            CREATE TABLE test_inserts (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                timestamp_col TIMESTAMP NOT NULL DEFAULT now(),
                varchar_col VARCHAR(255) NOT NULL,
                text_col TEXT,
                integer_col INTEGER NOT NULL,
                bigint_col BIGINT,
                decimal_col DECIMAL(10, 2),
                boolean_col BOOLEAN DEFAULT false,
                jsonb_col JSONB,
                array_col INTEGER[],
                uuid_col UUID,
                date_col DATE,
                time_col TIME,
                double_col DOUBLE PRECISION,
                float_col REAL,
                created_at TIMESTAMP NOT NULL DEFAULT now(),
                updated_at TIMESTAMP NOT NULL DEFAULT now(),
                INDEX idx_timestamp (timestamp_col),
                INDEX idx_varchar (varchar_col),
                INDEX idx_integer (integer_col)
            )
            """);
    }
}
