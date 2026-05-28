package com.examai.exam_engine.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {

        try {
            String originalName = file.getOriginalFilename();

            if (originalName == null || file.isEmpty()) {
                return Map.of("error", "Invalid file");
            }

            String name = originalName.toLowerCase();
            System.out.println("=== FILE DEBUG ===");
            System.out.println("Original name: " + originalName);
            System.out.println("Content type: " + file.getContentType());
            System.out.println("File size: " + file.getSize());
            System.out.println("Name lowercase: " + name);
            System.out.println("Ends with .pdf: " + name.endsWith(".pdf"));
            System.out.println("Ends with .pptx: " + name.endsWith(".pptx"));
            System.out.println("Ends with .docx: " + name.endsWith(".docx"));
            String text;
            int totalPages = 1;

            // ================= PDF =================
            if (name.endsWith(".pdf")) {

                try (PDDocument document = PDDocument.load(file.getInputStream())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    text = stripper.getText(document);
                    totalPages = document.getNumberOfPages();
                }

            }

            // ================= DOCX =================
            else if (name.endsWith(".docx")) {

                try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
                     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {

                    text = extractor.getText();
                    // Estimate pages based on character count (~2500 chars per page)
                    totalPages = Math.max(1, text.length() / 2500);
                }

            }

            // ================= DOC (old format) =================
            else if (name.endsWith(".doc")) {
                return Map.of("error", "Old .doc format not supported. Please convert to .docx");
            }

            // ================= PPTX =================
            else if (name.endsWith(".pptx")) {

                StringBuilder sb = new StringBuilder();

                try (XMLSlideShow ppt = new XMLSlideShow(file.getInputStream())) {

                    ppt.getSlides().forEach(slide -> {
                        slide.getShapes().forEach(shape -> {
                            if (shape instanceof XSLFTextShape) {
                                String data = ((XSLFTextShape) shape).getText();
                                if (data != null && !data.isBlank()) {
                                    // FIX: Use ". " separator so sentences split correctly later
                                    sb.append(data.trim()).append(". ");
                                }
                            }
                        });
                    });

                    text = sb.toString();
                    totalPages = ppt.getSlides().size();
                }

            }

            // ================= TXT =================
            else if (name.endsWith(".txt")) {

                text = new String(file.getBytes(), StandardCharsets.UTF_8);
                totalPages = Math.max(1, text.length() / 2000);

            }

            else {
                return Map.of("error", "Unsupported file format. Please upload PDF, DOCX, PPTX, or TXT.");
            }

            // ================= CLEAN TEXT =================
            // Normalize whitespace but preserve sentence boundaries for splitting
            text = text.replaceAll("[ \\t]+", " ")          // collapse spaces/tabs only
                    .replaceAll("\\r\\n|\\r", "\n")       // normalize line endings
                    .trim();

            if (text.isBlank()) {
                return Map.of("error", "No readable text found in the uploaded file.");
            }

            System.out.println("File: " + originalName + " | Extracted text length: " + text.length() + " | Pages: " + totalPages);

            // ================= QUESTION COUNT BY PAGE SIZE =================
            int vivaCount     = 15;
            int oneMarkCount  = 10;
            int threeMarkCount = 8;
            int fiveMarkCount  = 5;
            int tenMarkCount   = 2;

            if (totalPages >= 10 && totalPages <= 25) {
                vivaCount = 25; oneMarkCount = 15; threeMarkCount = 12;
                fiveMarkCount = 8; tenMarkCount = 5;
            } else if (totalPages >= 26 && totalPages <= 50) {
                vivaCount = 30; oneMarkCount = 20; threeMarkCount = 15;
                fiveMarkCount = 10; tenMarkCount = 5;
            } else if (totalPages >= 100) {
                vivaCount = 50; oneMarkCount = 30; threeMarkCount = 20;
                fiveMarkCount = 15; tenMarkCount = 10;
            }

            // ================= TOPIC EXTRACTION =================
            // Split on sentence-ending punctuation AND newlines (handles all formats)
            String[] sentences = text.split("[.!?\\n]+");

            List<String> topics = new ArrayList<>();
            LinkedHashSet<String> unique = new LinkedHashSet<>();

            List<String> keywords = Arrays.asList(
                    "definition", "advantage", "disadvantage", "application",
                    "working", "principle", "algorithm", "process", "types",
                    "features", "components", "steps", "benefits", "formula",
                    "important", "uses", "example", "method", "technique",
                    "concept", "theory", "function", "purpose", "role"
            );

            // FIX: Lower threshold for non-PDF formats (notes/slides use simpler language)
            int scoreThreshold = name.endsWith(".pdf") ? 3 : 1;

            for (String s : sentences) {
                s = s.trim();

                // FIX: Widened length range (was 30–250) to catch short note-style sentences
                if (s.length() < 20 || s.length() > 350) continue;

                String lower = s.toLowerCase();
                int score = 0;

                for (String k : keywords) {
                    if (lower.contains(k)) score += 2;
                }

                if (lower.contains("defined as") ||
                        lower.contains("refers to") ||
                        lower.contains("known as") ||
                        lower.contains("is used to") ||
                        lower.contains("can be") ||
                        lower.contains("it is")) {
                    score += 5;
                }

                if (score >= scoreThreshold) unique.add(s);
            }

            topics.addAll(unique);

            // Fallback: if still not enough topics, grab any reasonable sentence
            if (topics.size() < 20) {
                for (String s : sentences) {
                    s = s.trim();
                    // FIX: Widened range (was 35–200) to handle more content styles
                    if (s.length() > 20 && s.length() < 350 && !topics.contains(s)) {
                        topics.add(s);
                    }
                    if (topics.size() >= 60) break; // don't go overboard
                }
            }

            // Hard cap at 120 topics
            if (topics.size() > 120) {
                topics = topics.subList(0, 120);
            }

            System.out.println("Topics extracted: " + topics.size());

            if (topics.isEmpty()) {
                return Map.of("error", "Could not extract meaningful content from the file. Please check the file has readable text.");
            }

            // ================= BUILD VIVA QUESTIONS =================
            List<Map<String, String>> viva = new ArrayList<>();

            for (int i = 0; i < Math.min(vivaCount, topics.size()); i++) {
                String t = topics.get(i);
                viva.add(Map.of(
                        "question", "What is " + shortTopic(t) + "?",
                        "answer", generateAnswer(t, 1)
                ));
            }

            // ================= BUILD SUBJECTIVE QUESTIONS =================
            Map<String, List<Map<String, String>>> subjective = new HashMap<>();
            subjective.put("one_mark",    generateQuestions(topics, oneMarkCount,   1));
            subjective.put("three_mark",  generateQuestions(topics, threeMarkCount, 3));
            subjective.put("five_mark",   generateQuestions(topics, fiveMarkCount,  5));
            subjective.put("ten_mark",    generateQuestions(topics, tenMarkCount,   10));

            // ================= BUILD CHEAT SHEET =================
            List<Map<String, Object>> cheat = new ArrayList<>();

            for (int i = 0; i < Math.min(12, topics.size()); i++) {
                String t = topics.get(i);
                cheat.add(Map.of(
                        "title", shortTopic(t),
                        "points", List.of(
                                "Important topic for exams",
                                "Conceptually important",
                                "Frequently asked"
                        )
                ));
            }

            // ================= BUILD RESPONSE =================
            Map<String, Object> response = new HashMap<>();
            response.put("objective",   Map.of("viva", viva));
            response.put("subjective",  subjective);
            response.put("cheat_sheet", cheat);
            response.put("pages",       totalPages);

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", "Failed to process file: " + e.getMessage());
        }
    }

    // ================= HELPERS =================

    private List<Map<String, String>> generateQuestions(List<String> topics, int count, int marks) {

        List<Map<String, String>> list = new ArrayList<>();
        Random r = new Random();

        String[] starters1  = {"Define ", "What is ", "State "};
        String[] starters3  = {"Explain ", "Describe ", "Write about "};
        String[] starters5  = {"Explain in detail ", "Discuss ", "Illustrate "};
        String[] starters10 = {"Discuss in detail ", "Explain elaborately ", "Describe comprehensively "};

        for (int i = 0; i < Math.min(count, topics.size()); i++) {
            String t = shortTopic(topics.get(i));
            String q;

            if (marks == 1)       q = starters1[r.nextInt(starters1.length)]   + t;
            else if (marks == 3)  q = starters3[r.nextInt(starters3.length)]   + t;
            else if (marks == 5)  q = starters5[r.nextInt(starters5.length)]   + t;
            else                  q = starters10[r.nextInt(starters10.length)]  + t;

            list.add(Map.of(
                    "question", q,
                    "answer",   generateAnswer(t, marks)
            ));
        }

        return list;
    }

    private String generateAnswer(String text, int mark) {
        String cleaned = text.replaceAll("\\s+", " ").trim();

        if (mark == 1)
            return shorten(cleaned, 120);

        if (mark == 3)
            return "Definition:\n" + shorten(cleaned, 150) +
                    "\n\nKey Points:\n• Important concept\n• Exam relevant";

        if (mark == 5)
            return "Intro:\n" + shorten(cleaned, 180) +
                    "\n\nExplanation:\n• Key concept\n• Important for exams";

        return "Detailed Explanation:\n" + shorten(cleaned, 250) +
                "\n\nKey Points:\n• Important topic\n• Frequently asked";
    }

    private String shortTopic(String text) {
        String[] w = text.replaceAll("[^a-zA-Z0-9 ]", "").trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(w, Math.min(6, w.length)));
    }

    private String shorten(String s, int l) {
        return s.length() <= l ? s : s.substring(0, l) + "...";
    }
}