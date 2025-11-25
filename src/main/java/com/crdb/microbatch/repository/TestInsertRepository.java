package com.crdb.microbatch.repository;

import com.crdb.microbatch.model.TestInsert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;


/**
 * Repository for test insert operations.
 * 
 * <p>Handles single-row inserts into the test_inserts table.
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
     * Gets the total count of inserted records.
     * 
     * @return the total count
     */
    public long getCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_inserts", Long.class);
        return count != null ? count : 0L;
    }
}

