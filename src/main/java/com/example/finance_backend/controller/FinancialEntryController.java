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
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String tag
    ) {
        if (tag != null && !tag.isBlank()) {
            return ResponseEntity.ok(entryService.findByTag(userId, tag.trim()));
        }
        if (from != null && to != null) {
            return ResponseEntity.ok(entryService.findByDateRange(userId, from, to));
        }
        return ResponseEntity.ok(entryService.findAll(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FinancialEntryDto> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return entryService.findById(id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FinancialEntryDto> create(
            @Valid @RequestBody CreateEntryRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(entryService.create(request, userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FinancialEntryDto> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateEntryRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return ResponseEntity.ok(entryService.update(id, request, userId));
    }

    @PostMapping("/{id}/image")
    public ResponseEntity<FinancialEntryDto> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return ResponseEntity.ok(entryService.uploadImage(id, file, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        entryService.deleteById(id, userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalStateException(IllegalStateException e) {
        // Dùng cho các rule nghiệp vụ như: không cho chi vượt số dư
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
