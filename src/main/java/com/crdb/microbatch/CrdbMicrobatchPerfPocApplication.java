package com.crdb.microbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Spring Boot application for CRDB microbatch performance testing.
 * 
 * <p>This application uses VajraPulse to perform load testing on CockroachDB
 * with single-row inserts to measure microbatching effectiveness.
 */
@SpringBootApplication
@EnableScheduling
public class CrdbMicrobatchPerfPocApplication {

    /**
     * Main entry point for the application.
     * 
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(CrdbMicrobatchPerfPocApplication.class, args);
    }
}

