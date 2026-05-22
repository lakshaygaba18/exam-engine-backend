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

    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(240))
            .writeTimeout(Duration.ofSeconds(240))
            .build();

    private final Map<String, Map<String, Object>> memoryCache = new HashMap<>();

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
    }

    @PostMapping("/upload")
    private String extractTopic(String sentence) {
        String cleaned = sentence
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String[] words = cleaned.split(" ");

        List<String> stopWords = List.of(
                "the", "is", "are", "was", "were", "and", "or", "but",
                "this", "that", "these", "those", "with", "from", "into",
                "about", "because", "which", "while", "where", "when",
                "have", "has", "had", "can", "will", "shall", "should",
                "would", "could", "also", "more", "most", "very", "their",
                "there", "then", "than", "been", "being", "such", "only",
                "for", "you", "your", "its", "our", "they", "them"
        );

        for (String word : words) {
            String lower = word.toLowerCase();

            if (word.length() > 5 && !stopWords.contains(lower)) {
                return word;
            }
        }

        if (words.length >= 3) {
            return words[0] + " " + words[1] + " " + words[2];
        }

        return cleaned.length() > 25 ? cleaned.substring(0, 25) : cleaned;
    }

    private String generateVivaQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return "What does the PDF explain about " + topic + "?";
    }

    private String generateOneMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return "Define or state one key point about " + topic + ".";
    }

    private String generateThreeMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return "Explain " + topic + " briefly.";
    }

    private String generateFiveMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return "Explain the importance of " + topic + ".";
    }

    private String generateTenMarkQuestion(String sentence) {
        String topic = extractTopic(sentence);
        return "Discuss " + topic + " in detail with key points.";
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
            String outputText = cleanJson(extractOutputText(root));

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
        Map<String, Object> response = new HashMap<>();

        Map<String, Object> objective = new HashMap<>();

        objective.put("viva", List.of(
                Map.of("question", "What is an Operating System?", "answer", "System software that manages hardware and software resources."),
                Map.of("question", "What is DBMS?", "answer", "Database Management System used to store and manage data."),
                Map.of("question", "What is CPU scheduling?", "answer", "Method used by OS to allocate CPU to processes.")
        ));

        Map<String, Object> subjective = new HashMap<>();

        subjective.put("one_mark", List.of(
                Map.of("question", "Define Algorithm.", "answer", "A step-by-step procedure to solve a problem."),
                Map.of("question", "What is RAM?", "answer", "Temporary primary memory used during execution.")
        ));

        subjective.put("three_mark", List.of(
                Map.of("question", "Explain process states.", "answer", "Processes move through ready, running, waiting, and terminated states."),
                Map.of("question", "Explain primary key.", "answer", "A primary key uniquely identifies each row in a database table.")
        ));

        subjective.put("five_mark", List.of(
                Map.of("question", "Explain deadlock.", "answer", "Deadlock occurs when processes wait indefinitely for resources held by each other. It happens due to mutual exclusion, hold and wait, no preemption, and circular wait."),
                Map.of("question", "Explain normalization.", "answer", "Normalization organizes database tables to reduce redundancy and improve consistency using forms like 1NF, 2NF, and 3NF.")
        ));

        subjective.put("ten_mark", List.of(
                Map.of("question", "Explain DBMS architecture.", "answer", "DBMS architecture has external, conceptual, and internal levels. External level shows user views, conceptual level shows logical structure, and internal level manages physical storage."),
                Map.of("question", "Explain paging and segmentation.", "answer", "Paging divides memory into fixed-size pages and frames, while segmentation divides programs into logical parts like code, data, and stack.")
        ));

        response.put("objective", objective);
        response.put("subjective", subjective);

        response.put("cheat_sheet", List.of(
                Map.of(
                        "title", "Operating System",
                        "points", List.of(
                                "OS manages hardware and software.",
                                "CPU scheduling improves performance.",
                                "Deadlock occurs due to circular wait.",
                                "Paging reduces external fragmentation."
                        )
                ),
                Map.of(
                        "title", "DBMS",
                        "points", List.of(
                                "DBMS stores and manages data.",
                                "Primary key uniquely identifies rows.",
                                "Normalization reduces redundancy.",
                                "SQL is used for database operations."
                        )
                )
        ));

        response.put("mode", "fallback");
        return response;
    }
    private Map<String, Object> fallbackGenerateFromText(String text) {
        Map<String, Object> response = new HashMap<>();

        List<String> sentences = Arrays.stream(text.split("[.!?]"))
                .map(String::trim)
                .map(s -> s.replaceAll("\\s+", " "))
                .filter(s -> s.length() > 45 && s.length() < 260)
                .filter(s -> !s.matches(".*\\b(page|copyright|www|http|figure|table)\\b.*"))
                .distinct()
                .limit(30)
                .toList();

        if (sentences.isEmpty()) {
            return fallbackGenerate();
        }

        List<String> important = sentences.stream()
                .filter(s ->
                        s.toLowerCase().contains("important") ||
                                s.toLowerCase().contains("means") ||
                                s.toLowerCase().contains("defined") ||
                                s.toLowerCase().contains("helps") ||
                                s.toLowerCase().contains("because") ||
                                s.toLowerCase().contains("process") ||
                                s.toLowerCase().contains("method") ||
                                s.toLowerCase().contains("benefit") ||
                                s.toLowerCase().contains("role")
                )
                .toList();

        if (important.size() < 8) {
            important = sentences;
        }

        List<Map<String, String>> viva = new ArrayList<>();
        for (int i = 0; i < Math.min(10, important.size()); i++) {
            String point = important.get(i);

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
                        "• " + point + "\n" +
                                "• This point is important for understanding the topic.\n" +
                                "• It can be revised as a short-answer exam point."
                ));
            }

            if (i < 4) {
                fiveMark.add(Map.of(
                        "question", generateFiveMarkQuestion(point),
                        "answer",
                        "Introduction:\n" + point + "\n\n" +
                                "Explanation:\nThis concept is important because it supports the main theme of the PDF. It helps in understanding the topic clearly and can be used while writing exam answers.\n\n" +
                                "Conclusion:\nThis point should be revised as an important part of last-minute preparation."
                ));
            }

            if (i < 2) {
                tenMark.add(Map.of(
                        "question", generateTenMarkQuestion(point),
                        "answer",
                        "Introduction:\n" + point + "\n\n" +
                                "Detailed Explanation:\nThe PDF presents this as an important idea. It should be understood with its meaning, purpose, and practical importance. A good exam answer should explain the concept clearly and connect it with the broader topic.\n\n" +
                                "Key Points:\n" +
                                "• Understand the core meaning\n" +
                                "• Explain why it is important\n" +
                                "• Add supporting points from the PDF\n" +
                                "• Write the answer in a structured way\n\n" +
                                "Conclusion:\nThis topic is useful for both short-answer and long-answer preparation."
                ));
            }
        }

        subjective.put("one_mark", oneMark);
        subjective.put("three_mark", threeMark);
        subjective.put("five_mark", fiveMark);
        subjective.put("ten_mark", tenMark);

        List<Map<String, Object>> cheatSheet = new ArrayList<>();

        for (int i = 0; i < Math.min(8, important.size()); i++) {
            cheatSheet.add(Map.of(
                    "title", "Important Point " + (i + 1),
                    "points", List.of(
                            important.get(i),
                            "Revise this point before exam.",
                            "Can be used in short or long answers."
                    )
            ));
        }

        response.put("objective", objective);
        response.put("subjective", subjective);
        response.put("cheat_sheet", cheatSheet);
        response.put("mode", "pdf_fallback_quality");

        return response;
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