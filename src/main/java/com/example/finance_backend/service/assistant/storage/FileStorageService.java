package com.example.finance_backend.service.assistant.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

/**
 * Dịch vụ hỗ trợ lưu trữ tệp tin cục bộ.
 * Nhiệm vụ chính: Lưu các ảnh mã hóa Base64 từ mobile gửi lên và quản lý tệp tin.
 */
@Service
@Slf4j
public class FileStorageService {

    public String saveBase64Image(String base64Data) {
        if (base64Data == null || base64Data.isBlank())
            return null;
        try {
            // Xóa tiền tố nếu có (ví dụ: "data:image/png;base64,")
            String base64Part = base64Data;
            String extension = ".png"; // Default
            if (base64Data.contains(",")) {
                String header = base64Data.substring(0, base64Data.indexOf(","));
                base64Part = base64Data.substring(base64Data.indexOf(",") + 1);
                if (header.contains("image/jpeg"))
                    extension = ".jpg";
                else if (header.contains("image/gif"))
                    extension = ".gif";
                else if (header.contains("image/webp"))
                    extension = ".webp";
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Part);
            String dir = "uploads/";
            java.io.File d = new java.io.File(dir);
            if (!d.exists())
                d.mkdirs();

            String filename = UUID.randomUUID().toString() + extension;
            Path path = Paths.get(dir + filename);
            Files.write(path, imageBytes);

            return "/" + dir + filename;
        } catch (Exception e) {
            log.error("Failed to save AI assistant image: {}", e.getMessage());
            return null;
        }
    }

    public void deleteFile(String filePath) {
        if (filePath == null || filePath.isBlank())
            return;
        try {
            // Remove leading slash if exists
            String relative = filePath.startsWith("/") ? filePath.substring(1) : filePath;
            Path path = Paths.get(relative);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.error("Failed to delete local File: {}", filePath, e);
        }
    }
}
