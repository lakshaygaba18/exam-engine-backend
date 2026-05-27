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

    private final Map<String, Map<String, Object>> memoryCache =
            new HashMap<>();

    @GetMapping("/test")
    public String test() {
        return "Backend is working!";
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile file
    ) {

        try {

            File tempFile =
                    File.createTempFile("upload_", ".pdf");

            file.transferTo(tempFile);

            PDDocument document =
                    PDDocument.load(tempFile);

            int totalPages =
                    document.getNumberOfPages();

            PDFTextStripper stripper =
                    new PDFTextStripper();

            String text =
                    stripper.getText(document);

            document.close();

            text = text
                    .replaceAll("\\s+", " ")
                    .trim();


            int vivaCount = 15;
            int oneMarkCount = 10;
            int threeMarkCount = 8;
            int fiveMarkCount = 5;
            int tenMarkCount = 2;

            if (totalPages >= 10 && totalPages <= 25) {

                vivaCount = 25;
                oneMarkCount = 15;
                threeMarkCount = 12;
                fiveMarkCount = 8;
                tenMarkCount = 5;

            } else if (totalPages >= 26
                    && totalPages <= 50) {

                vivaCount = 30;
                oneMarkCount = 20;
                threeMarkCount = 15;
                fiveMarkCount = 10;
                tenMarkCount = 5;

            } else if (totalPages >= 100) {

                vivaCount = 50;
                oneMarkCount = 30;
                threeMarkCount = 20;
                fiveMarkCount = 15;
                tenMarkCount = 10;
            }

            String[] rawSentences =
                    text.split("[.]");

            List<String> importantTopics =
                    new ArrayList<>();

            for (String s : rawSentences) {

                s = s.trim();

                if (s.length() > 40
                        && s.length() < 500) {

                    importantTopics.add(s);
                }
            }

            LinkedHashSet<String> uniqueTopics =
                    new LinkedHashSet<>(importantTopics);

            importantTopics =
                    new ArrayList<>(uniqueTopics);

            if (importantTopics.size() > 120) {

                importantTopics =
                        importantTopics.subList(0, 120);
            }

            // VIVA QUESTIONS

            List<Map<String, String>> vivaQuestions =
                    new ArrayList<>();

            for (int i = 0;
                 i < Math.min(
                         vivaCount,
                         importantTopics.size()
                 );
                 i++) {

                String topic =
                        importantTopics.get(i);

                Map<String, String> q =
                        new HashMap<>();

                q.put(
                        "question",
                        "What is "
                                + shortTopic(topic)
                                + "?"
                );

                q.put(
                        "answer",
                        generateAnswer(topic, 1)
                );

                vivaQuestions.add(q);
            }

            // SUBJECTIVE

            Map<String,
                    List<Map<String, String>>>
                    subjective = new HashMap<>();

            subjective.put(
                    "one_mark",
                    generateQuestions(
                            importantTopics,
                            oneMarkCount,
                            "Define ",
                            1
                    )
            );

            subjective.put(
                    "three_mark",
                    generateQuestions(
                            importantTopics,
                            threeMarkCount,
                            "Explain ",
                            3
                    )
            );

            subjective.put(
                    "five_mark",
                    generateQuestions(
                            importantTopics,
                            fiveMarkCount,
                            "Describe ",
                            5
                    )
            );

            subjective.put(
                    "ten_mark",
                    generateQuestions(
                            importantTopics,
                            tenMarkCount,
                            "Discuss in detail ",
                            10
                    )
            );

            // CHEAT SHEET

            List<Map<String, Object>> cheatSheet =
                    new ArrayList<>();

            int cheatLimit =
                    Math.min(
                            15,
                            importantTopics.size()
                    );

            for (int i = 0;
                 i < cheatLimit;
                 i++) {

                String topic =
                        importantTopics.get(i);

                Map<String, Object> cheat =
                        new HashMap<>();

                cheat.put(
                        "topic",
                        "Topic " + (i + 1) + ": " + shortTopic(topic)
                );

                List<String> points =
                        new ArrayList<>();

                points.add(generateAnswer(topic, 1));
                points.add(generateAnswer(topic, 3));
                points.add(generateAnswer(topic, 5));

                cheat.put("points", points);

                cheatSheet.add(cheat);
            }

            Map<String, Object> objective =
                    new HashMap<>();

            objective.put(
                    "viva",
                    vivaQuestions
            );

            Map<String, Object> response =
                    new HashMap<>();

            response.put(
                    "objective",
                    objective
            );

            response.put(
                    "subjective",
                    subjective
            );

            response.put(
                    "cheat_sheet",
                    cheatSheet
            );

            response.put(
                    "pages",
                    totalPages
            );

            return response;

        } catch (Exception e) {

            e.printStackTrace();

            Map<String, Object> error =
                    new HashMap<>();

            error.put(
                    "error",
                    e.getMessage()
            );

            return error;
        }
    }

    private List<Map<String, String>>
    generateQuestions(
            List<String> topics,
            int count,
            String prefix,
            int markType
    ) {

        List<Map<String, String>> list =
                new ArrayList<>();

        for (int i = 0;
             i < Math.min(count, topics.size());
             i++) {

            String topic = topics.get(i);

            Map<String, String> q =
                    new HashMap<>();

            q.put(
                    "question",
                    prefix + shortTopic(topic)
            );

            q.put(
                    "answer",
                    generateAnswer(topic, markType)
            );

            list.add(q);
        }

        return list;
    }

    private String generateAnswer(
            String text,
            int markType
    ) {

        text = text
                .replaceAll("\\s+", " ")
                .trim();

        if (markType == 1) {

            return text.length() > 120
                    ? text.substring(0, 120) + "..."
                    : text;
        }

        if (markType == 3) {

            return "Introduction:\n"
                    + shorten(text, 180)
                    + "\n\nKey Points:\n• Important concept\n• Frequently asked in exams\n• Useful for revision";
        }

        if (markType == 5) {

            return "Introduction:\n"
                    + shorten(text, 250)
                    + "\n\nExplanation:\n"
                    + shorten(text, 220)
                    + "\n\nConclusion:\nThis is an important topic for exams and understanding the subject fundamentals.";
        }

        return "Introduction:\n"
                + shorten(text, 300)
                + "\n\nDetailed Explanation:\n"
                + shorten(text, 350)
                + "\n\nImportant Points:\n"
                + "• Important theoretical concept\n"
                + "• Frequently asked in university exams\n"
                + "• Helpful for viva and subjective preparation\n"
                + "• Important for conceptual understanding\n"
                + "\nConclusion:\n"
                + "This topic plays an important role in understanding the complete chapter and is highly important from examination perspective.";
    }
    private String shorten(String text, int limit) {

        if (text.length() <= limit) {
            return text;
        }

        return text.substring(0, limit) + "...";
    }

    private String shortTopic(String text) {

        String cleaned =
                text.replaceAll(
                                "[^a-zA-Z0-9 ]",
                                " "
                        )
                        .replaceAll("\\s+", " ")
                        .trim();

        String[] words =
                cleaned.split(" ");

        StringBuilder sb =
                new StringBuilder();

        for (int i = 0;
             i < Math.min(8, words.length);
             i++) {

            sb.append(words[i]).append(" ");
        }

        return sb.toString().trim();
    }

    private String md5(String input) {

        try {

            MessageDigest md =
                    MessageDigest.getInstance("MD5");

            byte[] messageDigest =
                    md.digest(input.getBytes());

            StringBuilder sb =
                    new StringBuilder();

            for (byte b : messageDigest) {

                sb.append(
                        String.format("%02x", b)
                );
            }

            return sb.toString();

        } catch (Exception e) {

            return input;
        }
    }
}