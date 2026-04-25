package com.examai.exam_engine.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/questions")
public class QuestionController {

    @PostMapping("/generate")
    public Map<String, Object> generateQuestions(@RequestBody String text) {

        Map<String, Object> result = new HashMap<>();

        // Dummy logic (abhi basic)
        List<String> oneMark = Arrays.asList(
                "What is the main topic of the document?",
                "Define the key concept mentioned."
        );

        List<String> threeMark = Arrays.asList(
                "Explain the main idea in short.",
                "Describe any one concept briefly."
        );

        List<String> fiveMark = Arrays.asList(
                "Explain the topic in detail.",
                "Discuss the major points from the document."
        );

        List<String> tenMark = Arrays.asList(
                "Write a detailed explanation of the entire document.",
                "Analyze and summarize the document."
        );

        result.put("1_mark", oneMark);
        result.put("3_mark", threeMark);
        result.put("5_mark", fiveMark);
        result.put("10_mark", tenMark);

        return result;
    }
}