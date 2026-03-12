package com.example.finance_backend.service;

import com.example.finance_backend.dto.CreateEntryRequest;
import com.example.finance_backend.dto.FinancialEntryDto;
import com.example.finance_backend.entity.EntrySource;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.repository.FinancialEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FinancialEntryService {

    private final FinancialEntryRepository entryRepository;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findAll() {
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findAllByOrderByTransactionDateDescCreatedAtDesc().stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findByDateRange(LocalDate start, LocalDate end) {
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findByTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(start, end)
                .stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findByTag(String tag) {
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findByTagContaining(tag).stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<FinancialEntryDto> findById(Long id) {
        return entryRepository.findById(id)
                .map(e -> FinancialEntryDto.fromEntity(e, categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "")));
    }

    @Transactional
    public FinancialEntryDto create(CreateEntryRequest req) {
        if (!categoryService.getIdToNameMap().containsKey(req.getCategoryId())) {
            throw new IllegalArgumentException("Unknown Category ID: " + req.getCategoryId());
        }
        LocalDate date = req.getTransactionDate();
        EntrySource source = parseSource(req.getSource());

        FinancialEntry e = FinancialEntry.builder()
                .amount(req.getAmount())
                .note(req.getNote())
                .categoryId(req.getCategoryId())
                .transactionDate(date)
                .tags(join(req.getTags()))
                .mentions(join(req.getMentions()))
                .imageUrl(req.getImageUrl())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .source(source != null ? source : EntrySource.MANUAL)
                .build();
        e = entryRepository.save(e);
        String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "");
        return FinancialEntryDto.fromEntity(e, catName);
    }

    @Transactional
    public FinancialEntryDto update(Long id, CreateEntryRequest req) {
        if (!categoryService.getIdToNameMap().containsKey(req.getCategoryId())) {
            throw new IllegalArgumentException("Unknown Category ID: " + req.getCategoryId());
        }
        FinancialEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
        
        EntrySource source = parseSource(req.getSource());

        e.setAmount(req.getAmount());
        e.setNote(req.getNote());
        e.setCategoryId(req.getCategoryId());
        e.setTransactionDate(req.getTransactionDate());
        e.setTags(join(req.getTags()));
        e.setMentions(join(req.getMentions()));
        e.setImageUrl(req.getImageUrl());
        e.setLatitude(req.getLatitude());
        e.setLongitude(req.getLongitude());
        e.setSource(source != null ? source : EntrySource.MANUAL);

        e = entryRepository.save(e);
        String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "");
        return FinancialEntryDto.fromEntity(e, catName);
    }

    @Transactional
    public FinancialEntryDto uploadImage(Long id, org.springframework.web.multipart.MultipartFile file) {
        FinancialEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
        try {
            String dir = "uploads/";
            java.io.File d = new java.io.File(dir);
            if (!d.exists()) d.mkdirs();
            String extension = "";
            String originalFileName = file.getOriginalFilename();
            if (originalFileName != null && originalFileName.lastIndexOf(".") > 0) {
                extension = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            String filename = java.util.UUID.randomUUID().toString() + extension;
            java.nio.file.Path path = java.nio.file.Paths.get(dir + filename);
            java.nio.file.Files.copy(file.getInputStream(), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            e.setImageUrl("/" + dir + filename);
            e = entryRepository.save(e);
            String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "");
            return FinancialEntryDto.fromEntity(e, catName);
        } catch (java.io.IOException ex) {
            throw new RuntimeException("Upload failed", ex);
        }
    }

    @Transactional
    public void deleteById(Long id) {
        entryRepository.deleteById(id);
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return list.stream().map(s -> s.replace(",", "_")).collect(Collectors.joining(","));
    }

    private static EntrySource parseSource(String s) {
        if (s == null || s.isBlank()) return EntrySource.MANUAL;
        try {
            return EntrySource.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntrySource.MANUAL;
        }
    }
}
