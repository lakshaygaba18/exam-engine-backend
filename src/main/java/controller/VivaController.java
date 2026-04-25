package com.examai.exam_engine.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/viva")
public class VivaController {

    List<Map<String, Object>> questions = new ArrayList<>();
    List<Map<String, Object>> askedQuestions = new ArrayList<>();
    Map<String, Object> currentQuestion;

    int score = 0;

    public VivaController() {

        questions.add(createQuestion(
                "What is Artificial Intelligence?",
                Arrays.asList("A branch of computer science", "Hardware", "Language", "None"),
                "A branch of computer science"
        ));

        questions.add(createQuestion(
                "What is Java?",
                Arrays.asList("Programming language", "Database", "OS", "Browser"),
                "Programming language"
        ));

        questions.add(createQuestion(
                "What is CPU?",
                Arrays.asList("Processor", "Memory", "Storage", "Input device"),
                "Processor"
        ));
    }

    private Map<String, Object> createQuestion(String q, List<String> options, String ans) {
        Map<String, Object> map = new HashMap<>();
        map.put("question", q);
        map.put("options", options);
        map.put("answer", ans);
        return map;
    }

    @GetMapping("/start")
    public Map<String, Object> startViva() {

        if (askedQuestions.size() == questions.size()) {

            int total = questions.size();
            int percentage = (score * 100) / total;

            String resultText;

            if (percentage >= 80) {
                resultText = "Excellent";
            } else if (percentage >= 50) {
                resultText = "Good";
            } else {
                resultText = "Needs Improvement";
            }

            return Map.of(
                    "message", "Test Finished",
                    "score", score + "/" + total,
                    "percentage", percentage + "%",
                    "result", resultText
            );
        }

        Random rand = new Random();

        do {
            currentQuestion = questions.get(rand.nextInt(questions.size()));
        } while (askedQuestions.contains(currentQuestion));

        askedQuestions.add(currentQuestion);

        Map<String, Object> response = new HashMap<>();
        response.put("question", currentQuestion.get("question"));
        response.put("options", currentQuestion.get("options"));

        return response;
    }

    @PostMapping("/check")
    public Map<String, Object> checkAnswer(@RequestBody Map<String, String> request) {

        String userAnswer = request.get("answer");
        String correctAnswer = (String) currentQuestion.get("answer");

        Map<String, Object> result = new HashMap<>();

        if (userAnswer != null && userAnswer.equals(correctAnswer)) {
            score++;
            result.put("result", "Correct");
        } else {
            result.put("result", "Wrong");
            result.put("correctAnswer", correctAnswer);
        }

        return result;
    }
}