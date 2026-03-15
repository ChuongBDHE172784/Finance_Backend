package com.example.finance_backend.service;

import com.example.finance_backend.dto.CreateEntryRequest;
import com.example.finance_backend.dto.FinancialEntryDto;
import com.example.finance_backend.entity.EntrySource;
import com.example.finance_backend.entity.EntryType;
import com.example.finance_backend.entity.FinancialEntry;
import com.example.finance_backend.entity.Account;
import com.example.finance_backend.repository.FinancialEntryRepository;
import com.example.finance_backend.repository.AccountRepository;
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
    private final AccountRepository accountRepository;
    private final CategoryService categoryService;

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findAll(Long userId) {
        if (userId == null) return List.of();
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findByUserIdOrderByTransactionDateDescCreatedAtDesc(userId).stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findByDateRange(Long userId, LocalDate start, LocalDate end) {
        if (userId == null) return List.of();
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDescCreatedAtDesc(userId, start, end)
                .stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FinancialEntryDto> findByTag(Long userId, String tag) {
        if (userId == null) return List.of();
        var idToName = categoryService.getIdToNameMap();
        return entryRepository.findByUserIdAndTagContaining(userId, tag).stream()
                .map(e -> FinancialEntryDto.fromEntity(e, idToName.getOrDefault(e.getCategoryId(), "")))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<FinancialEntryDto> findById(Long id, Long userId) {
        return entryRepository.findById(id)
                .filter(e -> userId == null || userId.equals(e.getUserId()))
                .map(e -> FinancialEntryDto.fromEntity(e, categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "")));
    }

    @Transactional
    public FinancialEntryDto create(CreateEntryRequest req, Long userId) {
        if (!categoryService.getIdToNameMap().containsKey(req.getCategoryId())) {
            throw new IllegalArgumentException("Unknown Category ID: " + req.getCategoryId());
        }
        Account account = accountRepository.findById(req.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (userId != null && !userId.equals(account.getUserId())) {
            throw new IllegalArgumentException("Account not found");
        }

        EntryType type = EntryType.valueOf(req.getType().toUpperCase());
        LocalDate date = req.getTransactionDate();
        EntrySource source = parseSource(req.getSource());

        FinancialEntry e = FinancialEntry.builder()
                .type(type)
                .amount(req.getAmount())
                .note(req.getNote())
                .categoryId(req.getCategoryId())
                .accountId(req.getAccountId())
                .toAccountId(req.getToAccountId())
                .transactionDate(date)
                .tags(join(req.getTags()))
                .mentions(join(req.getMentions()))
                .imageUrl(req.getImageUrl())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .source(source != null ? source : EntrySource.MANUAL)
                .userId(userId)
                .build();
        
        // Update account balance
        if (type == EntryType.INCOME) {
            account.setBalance(account.getBalance().add(req.getAmount()));
        } else if (type == EntryType.EXPENSE) {
            account.setBalance(account.getBalance().subtract(req.getAmount()));
        }
        accountRepository.save(account);

        e = entryRepository.save(e);
        String catName = categoryService.getIdToNameMap().getOrDefault(e.getCategoryId(), "");
        return FinancialEntryDto.fromEntity(e, catName);
    }

    @Transactional
    public FinancialEntryDto update(Long id, CreateEntryRequest req, Long userId) {
        if (!categoryService.getIdToNameMap().containsKey(req.getCategoryId())) {
            throw new IllegalArgumentException("Unknown Category ID: " + req.getCategoryId());
        }
        FinancialEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
        if (userId != null && !userId.equals(e.getUserId())) {
            throw new IllegalArgumentException("Entry not found");
        }

        Long oldAccountId = e.getAccountId();
        EntryType oldType = e.getType();
        if (oldAccountId != null) {
            Account oldAccount = accountRepository.findById(oldAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Old account not found"));
            if (oldType == EntryType.INCOME) {
                oldAccount.setBalance(oldAccount.getBalance().subtract(e.getAmount()));
            } else if (oldType == EntryType.EXPENSE) {
                oldAccount.setBalance(oldAccount.getBalance().add(e.getAmount()));
            }
            accountRepository.save(oldAccount);
        }

        // Apply new balance
        Account newAccount = accountRepository.findById(req.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("New account not found"));
        
        EntryType newType = EntryType.valueOf(req.getType().toUpperCase());
        if (newType == EntryType.INCOME) {
            newAccount.setBalance(newAccount.getBalance().add(req.getAmount()));
        } else if (newType == EntryType.EXPENSE) {
            newAccount.setBalance(newAccount.getBalance().subtract(req.getAmount()));
        }
        accountRepository.save(newAccount);

        EntrySource source = parseSource(req.getSource());

        e.setAmount(req.getAmount());
        e.setNote(req.getNote());
        e.setCategoryId(req.getCategoryId());
        e.setAccountId(req.getAccountId());
        e.setType(newType);
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
    public FinancialEntryDto uploadImage(Long id, org.springframework.web.multipart.MultipartFile file, Long userId) {
        FinancialEntry e = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Entry not found"));
        if (userId != null && !userId.equals(e.getUserId())) {
            throw new IllegalArgumentException("Entry not found");
        }
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
    public void deleteById(Long id, Long userId) {
        FinancialEntry e = entryRepository.findById(id).orElse(null);
        if (e != null && (userId == null || userId.equals(e.getUserId()))) {
            Long accountId = e.getAccountId();
            EntryType type = e.getType();
            if (accountId != null) {
                Account account = accountRepository.findById(accountId).orElse(null);
                if (account != null && type != null) {
                    if (type == EntryType.INCOME) {
                        account.setBalance(account.getBalance().subtract(e.getAmount()));
                    } else if (type == EntryType.EXPENSE) {
                        account.setBalance(account.getBalance().add(e.getAmount()));
                    }
                    accountRepository.save(account);
                }
            }
            entryRepository.deleteById(id);
        }
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
