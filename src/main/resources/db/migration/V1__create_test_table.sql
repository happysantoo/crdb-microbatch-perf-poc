-- Create a non-trivial table with 15+ columns and UUID primary key
CREATE TABLE IF NOT EXISTS test_inserts (
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
);

