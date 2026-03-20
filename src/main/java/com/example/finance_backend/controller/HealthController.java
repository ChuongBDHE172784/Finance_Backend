package com.example.finance_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Endpoint to check if the app and database are alive.
     * Pinging this every 14 mins prevents Render and Neon from sleeping.
     */
    @GetMapping("/health")
    public Map<String, String> healthCheck() {
        try {
            // Ping the database to ensure it's "awake"
            jdbcTemplate.execute("SELECT 1");
            return Map.of(
                "status", "UP",
                "database", "Connected",
                "message", "Service is running"
            );
        } catch (Exception e) {
            return Map.of(
                "status", "DOWN",
                "database", "Disconnected",
                "error", e.getMessage()
            );
        }
    }
}
