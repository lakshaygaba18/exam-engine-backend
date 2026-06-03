package com.examai.exam_engine.controller;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@EnableScheduling
@RestController
@RequestMapping("/file")
public class FileUploadController {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ================= IN-MEMORY CACHE =================
    private static final Map<String, Map<String, Object>> responseCache = new ConcurrentHashMap<>();

    // ================= KEEP ALIVE PING =================
    @Scheduled(fixedDelay = 840000) // every 14 minutes
    public void keepAlive() {
        try {
            new RestTemplate().getForObject(
                "https://exam-engine-backend.onrender.com/file/test",
                String.class
            );
            System.out.println("Keep-alive ping sent");
        } catch (Exception e) {
            System.out.println("Keep-alive failed: " + e.getMessage());
        }
    }

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
            String text;
            int totalPages = 1;

            // ================= PDF =================
            if (name.endsWith(".pdf")) {
                try (PDDocument document = PDDocument.load(file.getInputStream())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    text = stripper.getText(document);
                    totalPages = document.getNumberOfPages();
                } catch (Exception e) {
                    return Map.of("error", "Could not read PDF. File may be corrupted or password-protected.");
                }
            }

            // ================= DOCX =================
            else if (name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
                     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                    text = extractor.getText();
                    totalPages = Math.max(1, text.length() / 2500);
                }
            }

            // ================= DOC =================
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

            // ================= PAGE LIMIT CHECK =================
            if (totalPages > 150) {
                return Map.of("error", "Document too large. Maximum 150 pages allowed. Your document has " + totalPages + " pages. Please upload a shorter document or split it into parts.");
            }

            // ================= CLEAN TEXT =================
            text = text.replaceAll("[ \\t]+", " ")
                       .replaceAll("\\r\\n|\\r", "\n")
                       .trim();

            if (text.isBlank()) {
                return Map.of("error", "No readable text found in the uploaded file.");
            }

            if (text.length() > 12000) {
                text = text.substring(0, 12000);
            }

            System.out.println("File: " + originalName + " | Text length: " + text.length() + " | Pages: " + totalPages);

            // ================= CACHE CHECK =================
            String cacheKey = getMD5(text);

            if (cacheKey != null && responseCache.containsKey(cacheKey)) {
                System.out.println("Cache HIT for: " + originalName);
                Map<String, Object> cached = new HashMap<>(responseCache.get(cacheKey));
                cached.put("pages", totalPages);
                return cached;
            }

            System.out.println("Cache MISS for: " + originalName);

            // ================= QUESTION COUNT BY PAGE SIZE =================
            int vivaCount      = 15;
            int oneMarkCount   = 10;
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

            // ================= OPENAI API CALL =================
            String apiKey = System.getenv("OPENAI_API_KEY");

            if (apiKey == null || apiKey.isBlank()) {
                System.out.println("OPENAI_API_KEY not set, falling back to rule-based generation");
                return fallbackGeneration(text, totalPages, vivaCount, oneMarkCount, threeMarkCount, fiveMarkCount, tenMarkCount, name);
            }

            String prompt = buildPrompt(text, vivaCount, oneMarkCount, threeMarkCount, fiveMarkCount, tenMarkCount);
            String aiResponse = callOpenAI(apiKey, prompt);

            if (aiResponse == null) {
                System.out.println("OpenAI call failed, falling back");
                return fallbackGeneration(text, totalPages, vivaCount, oneMarkCount, threeMarkCount, fiveMarkCount, tenMarkCount, name);
            }

            Map<String, Object> parsed = parseAIResponse(aiResponse);

            if (parsed == null) {
                System.out.println("OpenAI response parse failed, falling back");
                return fallbackGeneration(text, totalPages, vivaCount, oneMarkCount, threeMarkCount, fiveMarkCount, tenMarkCount, name);
            }

            // ================= STORE IN CACHE =================
            if (cacheKey != null) {
                responseCache.put(cacheKey, new HashMap<>(parsed));
                System.out.println("Cached result for: " + originalName);
            }

            parsed.put("pages", totalPages);
            return parsed;

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", "Failed to process file: " + e.getMessage());
        }
    }

    // ================= BUILD PROMPT =================
    private String buildPrompt(String text, int vivaCount, int oneMarkCount,
                                int threeMarkCount, int fiveMarkCount, int tenMarkCount) {
        return String.format(
            "You are an expert exam question generator for university students.\n\n" +
            "Based on the following study notes, generate exam questions in STRICT JSON format.\n\n" +
            "RULES:\n" +
            "- Viva questions: single sentence answer, max 2 lines, direct and to the point\n" +
            "- 1 mark questions: 2-3 lines max, crisp definition or fact\n" +
           "- 3 mark questions: vary the answer format based on question type, minimum 8-10 lines:\n" +
"  * If 'difference/compare/distinguish' -> use comparison with at least 4 bullet points showing both sides clearly\n" +
"  * If 'list/state/mention' -> use 4-5 bullet points, each 2 lines with explanation\n" +
"  * If 'explain/describe/write note' -> write a detailed short note of 8-10 lines as a paragraph\n" +
"  * If 'define' -> give definition + 3-4 key points with brief explanation each\n" +
            "- 5 mark questions: 10-15 lines, include definition + explanation + bullet points + example\n" +
            "- 10 mark questions: very detailed answer of minimum 25-30 lines covering: introduction, detailed explanation, types/classifications if any, at least 5 bullet points, advantages AND disadvantages, real-world applications, diagram description if applicable, and conclusion\n" +
            "- Cheat sheet: for each entry provide:\n" +
            "  * topic: short topic name (3-5 words)\n" +
            "  * summary: 2-3 lines covering the most important fact, formula, or definition\n" +
            "  * If the topic has a formula -> include it in summary\n" +
            "  * If the topic has an important date or number -> include it\n" +
            "  * If the topic is a process -> give steps in 1 line each\n" +
            "  * Write like a student's last-minute revision note, not a textbook\n" +
            "- Only generate questions from the actual content provided\n" +
            "- Do not add any text before or after the JSON\n\n" +
            "Generate exactly:\n" +
            "- %d viva questions\n" +
            "- %d one mark questions\n" +
            "- %d three mark questions\n" +
            "- %d five mark questions\n" +
            "- %d ten mark questions\n" +
            "- 15 cheat sheet entries\n\n" +
            "Return ONLY this JSON structure, nothing else:\n" +
            "{\n" +
            "  \"objective\": {\n" +
            "    \"viva\": [\n" +
            "      {\"question\": \"...\", \"answer\": \"...\"}\n" +
            "    ]\n" +
            "  },\n" +
            "  \"subjective\": {\n" +
            "    \"one_mark\": [{\"question\": \"...\", \"answer\": \"...\"}],\n" +
            "    \"three_mark\": [{\"question\": \"...\", \"answer\": \"...\", \"important\": true}],\n" +
            "    \"five_mark\": [{\"question\": \"...\", \"answer\": \"...\", \"important\": true}],\n" +
            "    \"ten_mark\": [{\"question\": \"...\", \"answer\": \"...\", \"important\": true}]\n" +
            "  },\n" +
            "  \"cheat_sheet\": [\n" +
            "    {\"topic\": \"...\", \"summary\": \"...\"}\n" +
            "  ]\n" +
            "}\n\n" +
            "STUDY NOTES:\n%s",
            vivaCount, oneMarkCount, threeMarkCount, fiveMarkCount, tenMarkCount, text
        );
    }

    // ================= CALL OPENAI API =================
    private String callOpenAI(String apiKey, String prompt) {
        try {
            String requestBody = String.format(
                "{\"model\": \"gpt-4o-mini\", \"messages\": [{\"role\": \"user\", \"content\": %s}], \"temperature\": 0.4, \"max_tokens\": 16000}",
                mapper.writeValueAsString(prompt)
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("OpenAI status: " + response.statusCode());

            if (response.statusCode() != 200) {
                System.out.println("OpenAI error: " + response.body());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode textNode = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

            if (textNode.isMissingNode()) {
                System.out.println("OpenAI response missing content node");
                return null;
            }

            return textNode.asText();

        } catch (Exception e) {
            System.out.println("OpenAI call exception: " + e.getMessage());
            return null;
        }
    }

    // ================= PARSE AI JSON RESPONSE =================
    private Map<String, Object> parseAIResponse(String rawText) {
        try {
            String cleaned = rawText.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode root = mapper.readTree(cleaned);

            if (!root.has("objective") || !root.has("subjective") || !root.has("cheat_sheet")) {
                System.out.println("AI JSON missing required keys");
                return null;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("objective", mapper.convertValue(root.get("objective"), Map.class));
            result.put("subjective", mapper.convertValue(root.get("subjective"), Map.class));
            result.put("cheat_sheet", mapper.convertValue(root.get("cheat_sheet"), List.class));

            return result;

        } catch (Exception e) {
            System.out.println("Parse error: " + e.getMessage());
            return null;
        }
    }

    // ================= MD5 HASH FOR CACHE KEY =================
    private String getMD5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================= FALLBACK (rule-based) =================
    private Map<String, Object> fallbackGeneration(String text, int totalPages,
            int vivaCount, int oneMarkCount, int threeMarkCount,
            int fiveMarkCount, int tenMarkCount, String name) {

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

        int scoreThreshold = name.endsWith(".pdf") ? 3 : 1;

        for (String s : sentences) {
            s = s.trim();
            if (s.length() < 20 || s.length() > 350) continue;
            String lower = s.toLowerCase();
            int score = 0;
            for (String k : keywords) { if (lower.contains(k)) score += 2; }
            if (lower.contains("defined as") || lower.contains("refers to") ||
                lower.contains("known as") || lower.contains("is used to") ||
                lower.contains("can be") || lower.contains("it is")) score += 5;
            if (score >= scoreThreshold) unique.add(s);
        }

        topics.addAll(unique);

        if (topics.size() < 20) {
            for (String s : sentences) {
                s = s.trim();
                if (s.length() > 20 && s.length() < 350 && !topics.contains(s)) topics.add(s);
                if (topics.size() >= 60) break;
            }
        }

        if (topics.size() > 120) topics = topics.subList(0, 120);
        if (topics.isEmpty()) return Map.of("error", "Could not extract content from file.");

        List<Map<String, String>> viva = new ArrayList<>();
        for (int i = 0; i < Math.min(vivaCount, topics.size()); i++) {
            String t = topics.get(i);
            viva.add(Map.of("question", "What is " + shortTopic(t) + "?", "answer", generateAnswer(t, 1)));
        }

        Map<String, List<Map<String, String>>> subjective = new HashMap<>();
        subjective.put("one_mark",    generateQuestions(topics, oneMarkCount,   1));
        subjective.put("three_mark",  generateQuestions(topics, threeMarkCount, 3));
        subjective.put("five_mark",   generateQuestions(topics, fiveMarkCount,  5));
        subjective.put("ten_mark",    generateQuestions(topics, tenMarkCount,   10));

        List<Map<String, Object>> cheat = new ArrayList<>();
        for (int i = 0; i < Math.min(15, topics.size()); i++) {
            String t = topics.get(i).trim();
            String topicName = extractTopicName(t);
            String summary = extractSummary(t);
            if (!topicName.isBlank() && !summary.isBlank()) {
                cheat.add(Map.of("topic", topicName, "summary", summary));
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("objective",   Map.of("viva", viva));
        response.put("subjective",  subjective);
        response.put("cheat_sheet", cheat);
        response.put("pages",       totalPages);
        return response;
    }

    // ================= HELPERS =================

    private List<Map<String, String>> generateQuestions(List<String> topics, int count, int marks) {
        List<Map<String, String>> list = new ArrayList<>();
        Random r = new Random();
        String[] s1  = {"Define ", "What is ", "State "};
        String[] s3  = {"Explain ", "Describe ", "Write about "};
        String[] s5  = {"Explain in detail ", "Discuss ", "Illustrate "};
        String[] s10 = {"Discuss in detail ", "Explain elaborately ", "Describe comprehensively "};

        for (int i = 0; i < Math.min(count, topics.size()); i++) {
            String t = shortTopic(topics.get(i));
            String q;
            if (marks == 1)       q = s1[r.nextInt(s1.length)]   + t;
            else if (marks == 3)  q = s3[r.nextInt(s3.length)]   + t;
            else if (marks == 5)  q = s5[r.nextInt(s5.length)]   + t;
            else                  q = s10[r.nextInt(s10.length)]  + t;
            list.add(Map.of("question", q, "answer", generateAnswer(t, marks)));
        }
        return list;
    }

    private String generateAnswer(String text, int mark) {
        String c = text.replaceAll("\\s+", " ").trim();
        if (mark == 1) return shorten(c, 100);
        if (mark == 3) return "Definition: " + shorten(c, 150) + "\n\nKey Points:\n• " + extractKeyPoint(c, 0) + "\n• " + extractKeyPoint(c, 1);
        if (mark == 5) return "Introduction:\n" + shorten(c, 180) + "\n\nExplanation:\n" + expandText(c, 200) + "\n\nKey Points:\n• " + extractKeyPoint(c, 0) + "\n• " + extractKeyPoint(c, 1) + "\n• " + extractKeyPoint(c, 2);
        return "Introduction:\n" + shorten(c, 180) + "\n\nDetailed Explanation:\n" + expandText(c, 250) + "\n\nImportant Points:\n• " + extractKeyPoint(c, 0) + "\n• " + extractKeyPoint(c, 1) + "\n• " + extractKeyPoint(c, 2) + "\n• " + extractKeyPoint(c, 3) + "\n\nConclusion:\n" + shorten(c, 120);
    }

    private String extractTopicName(String sentence) {
        String cleaned = sentence.replaceAll("[^a-zA-Z0-9 ]", " ").trim();
        String[] words = cleaned.split("\\s+");
        Set<String> skip = new HashSet<>(Arrays.asList("a","an","the","is","are","was","were","of","in","on","at","to","for","and","or"));
        List<String> meaningful = new ArrayList<>();
        for (String w : words) {
            if (w.length() > 2 && !skip.contains(w.toLowerCase())) meaningful.add(w);
            if (meaningful.size() == 5) break;
        }
        return String.join(" ", meaningful);
    }

    private String extractSummary(String sentence) {
        String cleaned = sentence.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= 120) return cleaned;
        String cut = cleaned.substring(0, 120);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > 60 ? cut.substring(0, lastSpace) + "..." : cut + "...";
    }

    private String extractKeyPoint(String text, int index) {
        String[] words = text.split("\\s+");
        int chunkSize = Math.max(8, words.length / 4);
        int start = Math.min(index * chunkSize, Math.max(0, words.length - chunkSize));
        int end = Math.min(start + chunkSize, words.length);
        String point = String.join(" ", Arrays.copyOfRange(words, start, end)).replaceAll("[^a-zA-Z0-9 ,.]", "").trim();
        return point.isEmpty() ? "Refer to notes for details" : shorten(point, 80);
    }

    private String expandText(String text, int maxLen) {
        String c = text.replaceAll("\\s+", " ").trim();
        if (c.length() >= maxLen) return shorten(c, maxLen);
        return c + " This concept is fundamental to understanding the subject.";
    }

    private String shortTopic(String text) {
        String[] w = text.replaceAll("[^a-zA-Z0-9 ]", "").trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(w, Math.min(6, w.length)));
    }

    private String shorten(String s, int l) {
        return s.length() <= l ? s : s.substring(0, l) + "...";
    }
}