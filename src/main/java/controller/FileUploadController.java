package com.examai.exam_engine.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    private final Map<String, Map<String, Object>> memoryCache = new HashMap<>();

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {

        String uploadDir = System.getProperty("user.dir") + "/uploads/";
        File dir = new File(uploadDir);

        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            String fileName = file.getOriginalFilename();

            if (fileName == null || fileName.isBlank()) {
                fileName = "default.pdf";
            }

            String filePath = uploadDir + System.currentTimeMillis() + "_" + fileName;
            file.transferTo(new File(filePath));

            String text = extractPdfText(filePath);

            if (text == null || text.trim().length() < 50) {
                return Map.of("error", "PDF content too low or unreadable.");
            }

            String normalizedText = normalizeText(text);
            String hash = sha256(normalizedText);

            if (memoryCache.containsKey(hash)) {
                return memoryCache.get(hash);
            }

            Map<String, Object> result = fallbackGenerateFromText(normalizedText);
            result.put("fallback", true);
            result.put("message", "AI disabled for now. Using PDF-based fallback data.");

            memoryCache.put(hash, result);

            return result;

        } catch (Exception e) {
            e.printStackTrace();

            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("fallback", true);
            error.put("data", fallbackGenerate());

            return error;
        }
    }

    private String extractPdfText(String filePath) throws Exception {
        File savedFile = new File(filePath);

        try (PDDocument document = PDDocument.load(savedFile)) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            return pdfStripper.getText(document);
        }
    }

    private String normalizeText(String text) {
        String cleaned = text
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\x00-\\x7F]", " ")
                .trim();

        int maxChars = 9000;

        if (cleaned.length() <= maxChars) {
            return cleaned;
        }

        String start = cleaned.substring(0, 4500);
        String middle = cleaned.substring(
                cleaned.length() / 2,
                Math.min(cleaned.length() / 2 + 2500, cleaned.length())
        );
        String end = cleaned.substring(Math.max(cleaned.length() - 2000, 0));

        return start + "\n\n" + middle + "\n\n" + end;
    }

    private Map<String, Object> fallbackGenerateFromText(String text) {

        Map<String, Object> response = new HashMap<>();

        List<String> headings = extractHeadings(text);
        List<String> sentences = Arrays.stream(text.split("[.!?\\n]"))
                .map(String::trim)
                .map(s -> s.replaceAll("\\s+", " "))
                .filter(s -> s.length() > 45 && s.length() < 260)
                .filter(s -> !isJunkLine(s))
                .distinct()
                .limit(45)
                .toList();

        if (sentences.isEmpty()) {
            return fallbackGenerate();
        }

        List<String> important = sentences.stream()
                .filter(s -> {

                    String x = s.toLowerCase();

                    return

                            x.contains("is") ||
                                    x.contains("are") ||
                                    x.contains("means") ||
                                    x.contains("defined") ||
                                    x.contains("refers") ||

                                    x.contains("important") ||
                                    x.contains("benefit") ||
                                    x.contains("advantage") ||
                                    x.contains("effect") ||
                                    x.contains("cause") ||

                                    x.contains("improve") ||
                                    x.contains("increase") ||
                                    x.contains("reduce") ||
                                    x.contains("control") ||

                                    x.contains("process") ||
                                    x.contains("method") ||
                                    x.contains("technique") ||
                                    x.contains("system") ||

                                    x.contains("health") ||
                                    x.contains("stress") ||
                                    x.contains("focus") ||
                                    x.contains("memory") ||
                                    x.contains("learning");

                })
                .distinct()
                .limit(20)
                .toList();

        if (important.size() < 8) {
            important = sentences;
        }
        if (!headings.isEmpty()) {
            List<String> mixed = new ArrayList<>();
            mixed.addAll(headings);
            mixed.addAll(important);
            important = mixed.stream().distinct().limit(25).toList();
        }

        List<Map<String, String>> viva = new ArrayList<>();
        Set<String> usedTopics = new HashSet<>();

        for (String point : important) {
            if (viva.size() >= 10) {
                break;
            }

            String topic = extractTopic(point).toLowerCase();

            if (usedTopics.contains(topic)) {
                continue;
            }

            usedTopics.add(topic);

            viva.add(Map.of(
                    "question", generateVivaQuestion(point),
                    "answer", point
            ));
        }

        Map<String, Object> objective = new HashMap<>();
        objective.put("viva", viva);

        Map<String, Object> subjective = new HashMap<>();

        List<Map<String, String>> oneMark = new ArrayList<>();
        List<Map<String, String>> threeMark = new ArrayList<>();
        List<Map<String, String>> fiveMark = new ArrayList<>();
        List<Map<String, String>> tenMark = new ArrayList<>();

        for (int i = 0; i < important.size(); i++) {

            String point = important.get(i);

            if (i < 6) {
                oneMark.add(Map.of(
                        "question", generateOneMarkQuestion(point),
                        "answer", point
                ));
            }

            if (i < 5) {
                threeMark.add(Map.of(
                        "question", generateThreeMarkQuestion(point),
                        "answer",
                        "Definition:\n" + point + "\n\n" +
                                "Key Points:\n" +
                                "• It explains an important part of the uploaded material\n" +
                                "• Helps in understanding the topic clearly\n" +
                                "• Useful for short-answer exam preparation"
                ));
            }

            if (i < 4) {
                fiveMark.add(Map.of(
                        "question", generateFiveMarkQuestion(point),
                        "answer",
                        "Introduction:\n" + point + "\n\n" +
                                "Explanation:\n" +
                                "This point is connected to the main topic of the PDF. It should be explained clearly with its meaning and purpose.\n\n" +
                                "Exam Relevance:\n" +
                                "• Helps in writing descriptive answers\n" +
                                "• Useful for quick revision\n" +
                                "• Can be expanded with examples if required\n\n" +
                                "Conclusion:\n" +
                                "This concept should be revised as an important exam point."
                ));
            }

            if (i < 2) {
                tenMark.add(Map.of(
                        "question", generateTenMarkQuestion(point),
                        "answer",
                        "Introduction:\n" + point + "\n\n" +
                                "Detailed Explanation:\n" +
                                "This topic should be explained in a structured way. Start with the meaning, then describe its role, importance, and related points from the PDF.\n\n" +
                                "Important Points:\n" +
                                "• Mention the core idea clearly\n" +
                                "• Explain why it matters\n" +
                                "• Add related points from the PDF\n" +
                                "• End with a short conclusion\n\n" +
                                "Conclusion:\n" +
                                "This topic is suitable for long-answer preparation and last-minute revision."
                ));
            }
        }

        subjective.put("one_mark", oneMark);
        subjective.put("three_mark", threeMark);
        subjective.put("five_mark", fiveMark);
        subjective.put("ten_mark", tenMark);

        List<Map<String, Object>> cheatSheet = new ArrayList<>();

        for (int i = 0; i < Math.min(8, important.size()); i++) {

            String topic = extractTopic(important.get(i));

            String shortPoint = important.get(i);

            if (shortPoint.length() > 140) {
                shortPoint = shortPoint.substring(0, 140) + "...";
            }

            cheatSheet.add(Map.of(
                    "title", topic,
                    "points", List.of(
                            shortPoint,
                            "Focus on the core meaning of " + topic + ".",
                            "Revise this topic before exams."
                    )
            ));
        }

        response.put("objective", objective);
        response.put("subjective", subjective);
        response.put("cheat_sheet", cheatSheet);
        response.put("mode", "pdf_fallback_quality");

        return response;
    }

    private Map<String, Object> fallbackGenerate() {

        Map<String, Object> response = new HashMap<>();

        Map<String, Object> objective = new HashMap<>();

        objective.put("viva", List.of(
                Map.of("question", "What is the main purpose of this PDF?", "answer", "To explain important concepts for exam preparation."),
                Map.of("question", "Why is revision important?", "answer", "Revision helps retain concepts and improve exam performance.")
        ));

        Map<String, Object> subjective = new HashMap<>();

        subjective.put("one_mark", List.of(
                Map.of("question", "Write one key point.", "answer", "The PDF contains important points for exam preparation.")
        ));

        subjective.put("three_mark", List.of(
                Map.of("question", "Write short notes on the topic.", "answer", "• Understand the definition\n• Learn key points\n• Revise examples")
        ));

        subjective.put("five_mark", List.of(
                Map.of("question", "Explain the topic in detail.", "answer", "The topic should be explained with definition, key points, examples, and conclusion.")
        ));

        subjective.put("ten_mark", List.of(
                Map.of("question", "Discuss the chapter comprehensively.", "answer", "A complete answer should include introduction, explanation, examples, applications, and conclusion.")
        ));

        response.put("objective", objective);
        response.put("subjective", subjective);
        response.put("cheat_sheet", List.of(
                Map.of(
                        "title", "Last Minute Revision",
                        "points", List.of("Revise definitions", "Focus on important concepts", "Practice writing answers")
                )
        ));

        response.put("mode", "fallback");

        return response;
    }
    private boolean isJunkLine(String s) {
        if (s == null || s.isBlank()) return true;

        String x = s.toLowerCase();

        if (x.contains("copyright")) return true;
        if (x.contains("www")) return true;
        if (x.contains("http")) return true;
        if (x.contains("page")) return true;
        if (x.contains("figure")) return true;
        if (x.contains("table")) return true;

        int digitCount = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) digitCount++;
        }

        return digitCount > s.length() / 3;
    }
    private List<String> extractHeadings(String text) {

        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<String> headings = new ArrayList<>();

        String[] lines = text.split("\\n");

        for (String line : lines) {

            String cleaned = line
                    .replaceAll("[^a-zA-Z0-9 ]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (cleaned.length() >= 4 &&
                    cleaned.length() <= 60 &&
                    cleaned.split(" ").length <= 6 &&
                    !isJunkLine(cleaned) &&
                    !cleaned.toLowerCase().startsWith("unit") &&
                    !cleaned.toLowerCase().startsWith("page") &&
                    !cleaned.toLowerCase().startsWith("chapter") &&
                    !cleaned.matches(".*\\d{2,}.*")) {

                headings.add(cleaned);
            }

            if (headings.size() >= 10) {
                break;
            }
        }

        return headings;
    }
    private String extractTopic(String sentence) {

        if (sentence == null || sentence.trim().isEmpty()) {
            return "the topic";
        }

        String cleaned = sentence
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.isEmpty()) {
            return "the topic";
        }

        String[] words = cleaned.split(" ");

        List<String> stopWords = List.of(
                "the", "is", "are", "was", "were", "and", "or", "but",
                "this", "that", "these", "those", "with", "from", "into",
                "about", "because", "which", "while", "where", "when",
                "have", "has", "had", "can", "will", "shall", "should",
                "would", "could", "also", "more", "most", "very", "their",
                "there", "then", "than", "been", "being", "such", "only",
                "for", "you", "your", "its", "our", "they", "them", "using",
                "used", "make", "makes", "made", "help", "helps", "important",
                "concept", "topic", "chapter", "point", "points", "pdf"
        );

        List<String> selected = new ArrayList<>();

        for (String word : words) {
            String lower = word.toLowerCase();

            if (
                    word.length() > 4 &&
                            !stopWords.contains(lower) &&
                            Character.isLetter(word.charAt(0))
            ) {
                selected.add(
                        word.substring(0, 1).toUpperCase() +
                                word.substring(1).toLowerCase()
                );
            }

            if (selected.size() == 3) {
                break;
            }
        }

        if (!selected.isEmpty()) {
            return String.join(" ", selected);
        }

        return "the topic";
    }

    private final List<String> vivaStarters = List.of(
            "What is ",
            "What does the PDF explain about ",
            "Why is ",
            "How does ",
            "What is the role of "
    );

    private final List<String> oneMarkStarters = List.of(
            "Define ",
            "State one point about ",
            "What is ",
            "Name the key idea of "
    );

    private final List<String> threeMarkStarters = List.of(
            "Explain ",
            "Write short notes on ",
            "Why is ",
            "How does "
    );

    private final List<String> fiveMarkStarters = List.of(
            "Explain the importance of ",
            "Discuss the role of ",
            "Describe ",
            "Explain the benefits of "
    );

    private final List<String> tenMarkStarters = List.of(
            "Discuss ",
            "Explain in detail ",
            "Write detailed notes on ",
            "Describe the significance of "
    );

    private String randomStarter(List<String> list, String topic) {
        int index = Math.abs(topic.hashCode()) % list.size();
        return list.get(index) + topic;
    }

    private String generateVivaQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return randomStarter(vivaStarters, topic) + "?";
    }

    private String generateOneMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return randomStarter(oneMarkStarters, topic) + ".";
    }

    private String generateThreeMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return randomStarter(threeMarkStarters, topic) + "?";
    }

    private String generateFiveMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return randomStarter(fiveMarkStarters, topic) + "?";
    }

    private String generateTenMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return randomStarter(tenMarkStarters, topic) + " in detail.";
    }

    private String sha256(String input) throws Exception {

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes());

        StringBuilder hex = new StringBuilder();

        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }
}