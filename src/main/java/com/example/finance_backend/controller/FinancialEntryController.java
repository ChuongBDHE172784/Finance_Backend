package com.example.finance_backend.controller;

import com.example.finance_backend.dto.CreateEntryRequest;
import com.example.finance_backend.dto.FinancialEntryDto;
import com.example.finance_backend.service.FinancialEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/entries")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class FinancialEntryController {

    private final FinancialEntryService entryService;

    @GetMapping
    public ResponseEntity<List<FinancialEntryDto>> getAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String tag
    ) {
        if (tag != null && !tag.isBlank()) {
            return ResponseEntity.ok(entryService.findByTag(tag.trim()));
        }
        if (from != null && to != null) {
            return ResponseEntity.ok(entryService.findByDateRange(from, to));
        }
        return ResponseEntity.ok(entryService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinancialEntryDto> getById(@PathVariable Long id) {
        return entryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FinancialEntryDto> create(@Valid @RequestBody CreateEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(entryService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FinancialEntryDto> update(@PathVariable Long id, @Valid @RequestBody CreateEntryRequest request) {
        return ResponseEntity.ok(entryService.update(id, request));
    }

    @PostMapping("/{id}/image")
    public ResponseEntity<FinancialEntryDto> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ResponseEntity.ok(entryService.uploadImage(id, file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        entryService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
