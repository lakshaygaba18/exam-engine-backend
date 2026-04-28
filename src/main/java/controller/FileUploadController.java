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
            Random random = new Random();


            for (String line : lines) {
                line = line.trim();

                if (line.length() > 40 && line.length() < 150) {

                    Map<String, Object> q = new HashMap<>();

                    String question = "Which of the following is correct about: " + line + "?";
                    q.put("question", question);

                    // ✅ Create better options
                    List<String> options = new ArrayList<>();

                    String correct = line;

                    // Generate distractors
                    String distractor1 = "This statement is partially incorrect: " + line.substring(0, Math.min(20, line.length()));
                    String distractor2 = "This refers to a different concept than: " + line.substring(0, Math.min(25, line.length()));
                    String distractor3 = "This is not related to the given concept";

                    options.add(correct);
                    options.add(distractor1);
                    options.add(distractor2);
                    options.add(distractor3);

                    // 🔥 Shuffle options
                    Collections.shuffle(options);

                    q.put("options", options);
                    q.put("answer", correct);

                    mcqs.add(q);
                }

                if (mcqs.size() >= 5) break;
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