package com.crdb.microbatch.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Entity representing a test insert record.
 * 
 * <p>This entity maps to the test_inserts table with 15+ columns
 * including a UUID primary key.
 */
public record TestInsert(
    UUID id,
    LocalDateTime timestampCol,
    String varcharCol,
    String textCol,
    Integer integerCol,
    Long bigintCol,
    BigDecimal decimalCol,
    Boolean booleanCol,
    String jsonbCol,
    List<Integer> arrayCol,
    UUID uuidCol,
    LocalDate dateCol,
    LocalTime timeCol,
    Double doubleCol,
    Float floatCol,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    /**
     * Creates a new TestInsert with generated UUID and timestamps.
     * 
     * @param varcharCol the varchar column value
     * @param textCol the text column value
     * @param integerCol the integer column value
     * @param bigintCol the bigint column value
     * @param decimalCol the decimal column value
     * @param booleanCol the boolean column value
     * @param jsonbCol the JSONB column value
     * @param arrayCol the array column value
     * @param uuidCol the UUID column value
     * @param dateCol the date column value
     * @param timeCol the time column value
     * @param doubleCol the double column value
     * @param floatCol the float column value
     * @return a new TestInsert instance
     */
    public static TestInsert create(
        String varcharCol,
        String textCol,
        Integer integerCol,
        Long bigintCol,
        BigDecimal decimalCol,
        Boolean booleanCol,
        String jsonbCol,
        List<Integer> arrayCol,
        UUID uuidCol,
        LocalDate dateCol,
        LocalTime timeCol,
        Double doubleCol,
        Float floatCol
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new TestInsert(
            UUID.randomUUID(),
            now,
            varcharCol,
            textCol,
            integerCol,
            bigintCol,
            decimalCol,
            booleanCol,
            jsonbCol,
            arrayCol,
            uuidCol,
            dateCol,
            timeCol,
            doubleCol,
            floatCol,
            now,
            now
        );
    }
}

