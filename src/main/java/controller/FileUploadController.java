package com.examai.exam_engine.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {

        String uploadDir = System.getProperty("user.dir") + "/uploads/";

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            String fileName = file.getOriginalFilename();
            if (fileName == null) {
                fileName = "default.pdf";
            }

            String filePath = uploadDir + fileName;

            // Save file
            file.transferTo(new File(filePath));

            // Read PDF
            File savedFile = new File(filePath);
            PDDocument document = PDDocument.load(savedFile);

            PDFTextStripper pdfStripper = new PDFTextStripper();
            String text = pdfStripper.getText(document);

            document.close();

            // 🔥 FIXED detection (relaxed)
            if (text == null || text.trim().length() < 30) {
                return Map.of("error", "PDF content too low or unreadable.");
            }

            // 🔥 MCQ Generation
            List<String> lines = Arrays.asList(text.split("\\."));

            List<Map<String, Object>> mcqs = new ArrayList<>();

            for (String line : lines) {
                line = line.trim();

                if (line.length() > 30 && line.length() < 120) {

                    Map<String, Object> q = new HashMap<>();

                    q.put("question", "What is " + line + "?");

                    List<String> options = Arrays.asList(
                            line,
                            "None of the above",
                            "All of the above",
                            "Irrelevant statement"
                    );

                    q.put("options", options);
                    q.put("answer", line);

                    mcqs.add(q);
                }

                if (mcqs.size() >= 3) break;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("mcqs", mcqs);

            return result;

        } catch (IOException e) {
            e.printStackTrace();
            return Map.of("error", e.getMessage());
        }
    }
}