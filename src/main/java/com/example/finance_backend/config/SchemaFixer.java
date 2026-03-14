package com.example.finance_backend.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
@RequiredArgsConstructor
public class SchemaFixer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixer.class);
    private static final String CONSTRAINT_NAME = "financial_entries_source_check";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) return;
        try {
            jdbcTemplate.execute("alter table financial_entries drop constraint if exists " + CONSTRAINT_NAME);
            jdbcTemplate.execute(
                    "alter table financial_entries add constraint " + CONSTRAINT_NAME +
                            " check (source in ('MANUAL','OCR','VOICE','CLIPBOARD','AI'))"
            );
        } catch (Exception e) {
            log.warn("SchemaFixer failed to update constraint {}", CONSTRAINT_NAME, e);
        }
    }

    private boolean isPostgres() {
        try (Connection conn = dataSource.getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            return name != null && name.toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("SchemaFixer could not detect DB vendor", e);
            return false;
        }
    }
}
