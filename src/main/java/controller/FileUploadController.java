package com.examai.exam_engine.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/file")
public class FileUploadController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(180))
            .writeTimeout(Duration.ofSeconds(180))
            .build();

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        String uploadDir = System.getProperty("user.dir") + "/uploads/";

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try {
            String fileName = file.getOriginalFilename();
            if (fileName == null) fileName = "default.pdf";

            String filePath = uploadDir + fileName;
            file.transferTo(new File(filePath));

            String text = extractPdfText(filePath);

            if (text == null || text.trim().length() < 100) {
                return Map.of("error", "PDF content too low or unreadable.");
            }

            List<Map<String, Object>> mcqs = generateMcqsWithOpenAI(text);

            Map<String, Object> result = new HashMap<>();
            result.put("mcqs", mcqs);
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("error", e.getMessage());
        }
    }

    private String extractPdfText(String filePath) throws IOException {
        File savedFile = new File(filePath);
        PDDocument document = PDDocument.load(savedFile);

        PDFTextStripper pdfStripper = new PDFTextStripper();
        String text = pdfStripper.getText(document);

        document.close();
        return text;
    }

    private List<Map<String, Object>> generateMcqsWithOpenAI(String text) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("OPENAI_API_KEY is missing.");
        }

        String cleanedText = text
                .replaceAll("\\s+", " ")
                .trim();

        if (cleanedText.length() > 12000) {
            cleanedText = cleanedText.substring(0, 12000);
        }

        String prompt = """
                You are an expert exam question generator.

                Generate 15 high-quality MCQs from the PDF content below.

                STRICT RULES:
                1. Questions must be logical and exam-relevant.
                2. Do NOT ask vague questions like "Which statement is correct?"
                3. Each question must test a clear concept from the content.
                4. Each MCQ must have exactly 4 options.
                5. All options must look relevant and believable.
                6. Do NOT use "All of the above" or "None of the above".
                7. Correct answer position must be randomly distributed.
                8. Options must be similar in length and style.
                9. Avoid copying huge sentences directly from the PDF.
                10. Return ONLY valid JSON.

                JSON format:
                {
                  "mcqs": [
                    {
                      "question": "question text",
                      "options": ["option A", "option B", "option C", "option D"],
                      "answer": "exact correct option text"
                    }
                  ]
                }

                PDF CONTENT:
                """ + cleanedText;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4.1-mini");
        requestBody.put("input", prompt);

        String jsonBody = mapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new RuntimeException("OpenAI error: " + response.code() + " " + body);
            }

            JsonNode root = mapper.readTree(body);
            String outputText = extractOutputText(root);

            outputText = cleanJson(outputText);

            JsonNode mcqRoot = mapper.readTree(outputText);
            JsonNode mcqsNode = mcqRoot.get("mcqs");

            if (mcqsNode == null || !mcqsNode.isArray()) {
                throw new RuntimeException("Invalid AI response format.");
            }

            List<Map<String, Object>> mcqs = mapper.convertValue(
                    mcqsNode,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            validateMcqs(mcqs);
            return mcqs;
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

    private void validateMcqs(List<Map<String, Object>> mcqs) {
        if (mcqs == null || mcqs.isEmpty()) {
            throw new RuntimeException("No MCQs generated.");
        }

        for (Map<String, Object> mcq : mcqs) {
            if (!mcq.containsKey("question") ||
                    !mcq.containsKey("options") ||
                    !mcq.containsKey("answer")) {
                throw new RuntimeException("Invalid MCQ structure.");
            }

            Object optionsObj = mcq.get("options");

            if (!(optionsObj instanceof List<?> options) || options.size() != 4) {
                throw new RuntimeException("Each MCQ must have exactly 4 options.");
            }

            String answer = String.valueOf(mcq.get("answer"));
            boolean answerExists = options.stream()
                    .anyMatch(option -> String.valueOf(option).equals(answer));

            if (!answerExists) {
                throw new RuntimeException("Answer must exactly match one option.");
            }
        }
    }
}