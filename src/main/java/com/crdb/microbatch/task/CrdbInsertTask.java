package com.crdb.microbatch.task;

import com.crdb.microbatch.model.TestInsert;
import com.crdb.microbatch.repository.TestInsertRepository;
import com.vajrapulse.api.Task;
import com.vajrapulse.api.TaskResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VajraPulse task for inserting single rows into CockroachDB.
 * 
 * <p>This task performs one insert operation per execution to test
 * microbatching effectiveness in CockroachDB.
 */
@Component
public class CrdbInsertTask implements Task {

    private final TestInsertRepository repository;
    private final MeterRegistry meterRegistry;
    private final Random random = new Random();
    
    private Counter insertCounter;
    private Counter errorCounter;
    private Timer insertTimer;
    private final AtomicLong successfulInserts = new AtomicLong(0);

    /**
     * Constructor for CrdbInsertTask.
     * 
     * @param repository the test insert repository
     * @param meterRegistry the Micrometer meter registry
     */
    public CrdbInsertTask(TestInsertRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void setup() throws Exception {
        initializeMetrics();
    }

    /**
     * Initializes all metrics counters and timers.
     */
    private void initializeMetrics() {
        insertCounter = createCounter("crdb.inserts.total", "Total number of inserts attempted");
        errorCounter = createCounter("crdb.inserts.errors", "Total number of insert errors");
        insertTimer = createTimer("crdb.inserts.duration", "Insert operation duration");
    }

    /**
     * Creates a counter metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @return the created counter
     */
    private Counter createCounter(String name, String description) {
        return Counter.builder(name)
            .description(description)
            .register(meterRegistry);
    }

    /**
     * Creates a timer metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @return the created timer
     */
    private Timer createTimer(String name, String description) {
        return Timer.builder(name)
            .description(description)
            .register(meterRegistry);
    }

    @Override
    public TaskResult execute() throws Exception {
        insertCounter.increment();
        return insertTimer.recordCallable(this::performInsert);
    }

    /**
     * Performs the actual database insert operation.
     * 
     * @return the task result
     */
    private TaskResult performInsert() {
        try {
            TestInsert testInsert = generateTestData();
            int rowsAffected = repository.insert(testInsert);
            return handleInsertResult(rowsAffected);
        } catch (Exception e) {
            errorCounter.increment();
            return TaskResult.failure(e);
        }
    }

    /**
     * Handles the result of an insert operation.
     * 
     * @param rowsAffected the number of rows affected
     * @return the task result
     */
    private TaskResult handleInsertResult(int rowsAffected) {
        if (rowsAffected == 1) {
            successfulInserts.incrementAndGet();
            return TaskResult.success();
        } else {
            errorCounter.increment();
            return TaskResult.failure(new IllegalStateException(
                "Expected 1 row affected, got: " + rowsAffected));
        }
    }

    /**
     * Gets the current count of successful inserts (in-memory).
     * 
     * @return the count of successful inserts
     */
    public long getSuccessfulInsertCount() {
        return successfulInserts.get();
    }

    @Override
    public void cleanup() throws Exception {
        // No cleanup needed
    }

    /**
     * Generates random test data for insertion.
     * 
     * @return a TestInsert with random data
     */
    private TestInsert generateTestData() {
        return TestInsert.create(
            generateVarchar(),
            generateText(),
            random.nextInt(1000000),
            random.nextLong(1000000L),
            generateDecimal(),
            random.nextBoolean(),
            generateJson(),
            generateArray(),
            UUID.randomUUID(),
            generateDate(),
            LocalTime.now(),
            random.nextDouble() * 1000,
            random.nextFloat() * 100
        );
    }

    /**
     * Generates a random varchar value.
     * 
     * @return the generated varchar string
     */
    private String generateVarchar() {
        return "varchar_" + random.nextInt(1000000);
    }

    /**
     * Generates a random text value.
     * 
     * @return the generated text string
     */
    private String generateText() {
        return "text_content_" + random.nextInt(1000000);
    }

    /**
     * Generates a random decimal value.
     * 
     * @return the generated BigDecimal
     */
    private BigDecimal generateDecimal() {
        return BigDecimal.valueOf(random.nextDouble() * 10000);
    }

    /**
     * Generates a random JSON string.
     * 
     * @return the generated JSON string
     */
    private String generateJson() {
        return "{\"key\": \"value_" + random.nextInt(1000) + "\"}";
    }

    /**
     * Generates a random integer array.
     * 
     * @return the generated array
     */
    private List<Integer> generateArray() {
        return List.of(
            random.nextInt(100),
            random.nextInt(100),
            random.nextInt(100)
        );
    }

    /**
     * Generates a random date.
     * 
     * @return the generated LocalDate
     */
    private LocalDate generateDate() {
        return LocalDate.now().minusDays(random.nextInt(365));
    }
}
