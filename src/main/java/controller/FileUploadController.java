package com.examai.exam_engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(240))
            .writeTimeout(Duration.ofSeconds(240))
            .build();

    private final Map<String, Map<String, Object>> memoryCache = new HashMap<>();

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

            if (text == null || text.trim().length() < 100) {
                return Map.of("error", "PDF content too low or unreadable.");
            }

            String normalizedText = normalizeText(text);
            String hash = sha256(normalizedText);

            if (memoryCache.containsKey(hash)) {
                return memoryCache.get(hash);
            }

            Map<String, Object> aiResult = generateExamPrepWithOpenAI(normalizedText);

            memoryCache.put(hash, aiResult);

            return aiResult;

        } catch (Exception e) {
            e.printStackTrace();

            Map<String, Object> fallback = new HashMap<>();
            fallback.put("error", e.getMessage());
            fallback.put("fallback", true);
            fallback.put("data", fallbackGenerate());

            return fallback;
        }
    }

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
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
        String middle = cleaned.substring(cleaned.length() / 2, Math.min(cleaned.length() / 2 + 2500, cleaned.length()));
        String end = cleaned.substring(Math.max(cleaned.length() - 2000, 0));

        return start + "\n\n" + middle + "\n\n" + end;
    }

    private Map<String, Object> generateExamPrepWithOpenAI(String text) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("OPENAI_API_KEY is missing.");
        }

        String prompt = """
                You are an expert exam preparation assistant.

                Generate exam-focused study material from the PDF content.

                Return ONLY valid JSON. No markdown. No explanation outside JSON.

                Required JSON structure:
                {
                  "objective": {
                    "viva": [
                      {
                        "question": "short viva question",
                        "answer": "one-line answer"
                      }
                    ]
                  },
                  "subjective": {
                    "one_mark": [
                      {
                        "question": "1 mark question",
                        "answer": "short answer"
                      }
                    ],
                    "three_mark": [
                      {
                        "question": "3 mark question",
                        "answer": "medium answer in 3-5 bullet points"
                      }
                    ],
                    "five_mark": [
                      {
                        "question": "5 mark question",
                        "answer": "detailed answer with headings/bullets"
                      }
                    ],
                    "ten_mark": [
                      {
                        "question": "10 mark question",
                        "answer": "long exam-style answer with structure, examples if relevant"
                      }
                    ]
                  },
                  "cheat_sheet": [
                    {
                      "title": "important topic/formula/definition",
                      "points": ["short point 1", "short point 2"]
                    }
                  ]
                }

                Strict rules:
                1. Focus only on important concepts from the PDF.
                2. Do not generate random or vague questions.
                3. Avoid irrelevant content.
                4. Viva answers must be one-liners.
                5. 1 mark answers must be short.
                6. 3 mark answers should be concise bullet points.
                7. 5 mark and 10 mark answers should be exam-ready.
                8. Cheat sheet must be last-minute revision friendly.
                9. Keep the output compact but useful.
                10. Generate:
                    - 12 viva questions
                    - 8 one_mark questions
                    - 6 three_mark questions
                    - 4 five_mark questions
                    - 2 ten_mark questions
                    - 8 cheat_sheet items

                PDF CONTENT:
                """ + text;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("input", prompt);

        String jsonBody = mapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(
                        jsonBody,
                        MediaType.parse("application/json")
                ))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI error: " + response.code() + " " + body);
            }

            JsonNode root = mapper.readTree(body);
            String outputText = extractOutputText(root);
            outputText = cleanJson(outputText);

            JsonNode resultNode = mapper.readTree(outputText);

            validateResult(resultNode);

            Map<String, Object> result = mapper.convertValue(resultNode, Map.class);
            result.put("mode", "qa_engine");

            return result;
        }
    }

    private String extractOutputText(JsonNode root) {
        JsonNode output = root.get("output");

        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode c : content) {
                        JsonNode textNode = c.get("text");
                        if (textNode != null) {
                            return textNode.asText();
                        }
                    }
                }
            }
        }

        JsonNode outputText = root.get("output_text");
        if (outputText != null) {
            return outputText.asText();
        }

        throw new RuntimeException("Could not extract AI output text.");
    }

    private String cleanJson(String text) {
        return text
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private void validateResult(JsonNode node) {
        if (node.get("objective") == null ||
                node.get("subjective") == null ||
                node.get("cheat_sheet") == null) {
            throw new RuntimeException("Invalid AI response structure.");
        }
    }

    private Map<String, Object> fallbackGenerate() {
        Map<String, Object> fallback = new HashMap<>();

        Map<String, Object> objective = new HashMap<>();
        objective.put("viva", List.of(
                Map.of("question", "What is the main purpose of this chapter?", "answer", "To explain the key concepts required for exam preparation."),
                Map.of("question", "Why is revision important?", "answer", "Revision helps retain concepts and improve exam performance.")
        ));

        Map<String, Object> subjective = new HashMap<>();
        subjective.put("one_mark", List.of(
                Map.of("question", "Define the main topic.", "answer", "It refers to the central concept discussed in the study material.")
        ));
        subjective.put("three_mark", List.of(
                Map.of("question", "Write short notes on the main concept.", "answer", "• Understand the definition\n• Learn the key points\n• Revise examples")
        ));
        subjective.put("five_mark", List.of(
                Map.of("question", "Explain the topic in detail.", "answer", "This topic should be explained with definition, key points, examples, and conclusion.")
        ));
        subjective.put("ten_mark", List.of(
                Map.of("question", "Discuss the chapter comprehensively.", "answer", "A complete answer should include introduction, explanation, important points, examples, applications, and conclusion.")
        ));

        fallback.put("objective", objective);
        fallback.put("subjective", subjective);
        fallback.put("cheat_sheet", List.of(
                Map.of("title", "Last minute tip", "points", List.of("Revise definitions", "Focus on important concepts", "Practice writing answers"))
        ));
        fallback.put("mode", "fallback");

        return fallback;
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