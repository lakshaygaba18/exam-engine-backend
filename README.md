<div align="center">

# ⚡ LastMinuteExam — Backend

### Spring Boot REST API powering LastMinuteExam

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=java&logoColor=white)](https://java.com)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![OpenAI](https://img.shields.io/badge/OpenAI-GPT--4o%20Mini-412991?style=for-the-badge&logo=openai&logoColor=white)](https://openai.com)
[![Docker](https://img.shields.io/badge/Docker-Deployed-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://docker.com)
[![Render](https://img.shields.io/badge/Render-Live-46E3B7?style=for-the-badge&logo=render&logoColor=white)](https://render.com)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)

**Live API:** `https://exam-engine-backend.onrender.com`

[📱 Flutter App](https://github.com/lakshaygaba18/LastMinuteExam) · [🐛 Report Bug](https://github.com/lakshaygaba18/LastMinuteExam-Backend/issues)

</div>

---

## 🎯 What It Does

This is the backend engine powering LastMinuteExam. It accepts uploaded documents, extracts text using Apache POI and PDFBox, sends it to OpenAI GPT-4o Mini with a carefully crafted prompt, and returns a structured JSON response containing:

- **Viva questions** — short, direct, oral-exam style
- **Subjective questions** — 1, 3, 5 and 10 mark with proper structured answers
- **Cheat sheet** — topic name + 2-3 line memory trigger per topic

---

## ✨ Features

| Feature | Description |
|---|---|
| 📄 **Multi-format Parsing** | PDF (PDFBox), DOCX (Apache POI), PPTX (Apache POI), TXT |
| 🤖 **AI Integration** | GPT-4o Mini via OpenAI API — genuine, topic-specific questions |
| ⚡ **Smart Caching** | MD5 hash of document text used as cache key — same document never hits API twice |
| 📏 **Page Limit Guard** | Documents over 150 pages rejected with clear error message |
| 🔄 **Rule-based Fallback** | If OpenAI fails, falls back to keyword-based generation automatically |
| 🏃 **Keep-Alive Ping** | Self-pings every 14 minutes to prevent Render cold starts |
| 🐳 **Dockerized** | Runs in Docker container on Render cloud |

---

## 🏗️ Architecture

```
LastMinuteExam-Backend (Spring Boot)
├── src/main/java/com/examai/exam_engine/
│   └── controller/
│       └── FileUploadController.java     # Core logic
│           ├── File parsing              # PDF / DOCX / PPTX / TXT
│           ├── Text extraction           # Apache PDFBox + POI
│           ├── Cache layer               # ConcurrentHashMap + MD5
│           ├── OpenAI API call           # GPT-4o Mini via HTTP
│           ├── JSON response parser      # Jackson ObjectMapper
│           ├── Fallback generator        # Rule-based keyword scoring
│           └── Keep-alive scheduler      # @Scheduled ping
├── Dockerfile                            # Docker container config
└── pom.xml                               # Maven dependencies
```

---

## 🔌 API Reference

### Health Check
```
GET /file/test
Response: "Backend is working!"
```

### Upload & Generate
```
POST /file/upload
Content-Type: multipart/form-data
Body: file = <PDF/DOCX/PPTX/TXT>

Response:
{
  "objective": {
    "viva": [
      { "question": "...", "answer": "..." }
    ]
  },
  "subjective": {
    "one_mark":   [{ "question": "...", "answer": "..." }],
    "three_mark": [{ "question": "...", "answer": "..." }],
    "five_mark":  [{ "question": "...", "answer": "..." }],
    "ten_mark":   [{ "question": "...", "answer": "..." }]
  },
  "cheat_sheet": [
    { "topic": "...", "summary": "..." }
  ],
  "pages": 10
}
```

---

## 🛠️ Tech Stack

| Technology | Purpose |
|---|---|
| Java 17 | Core language |
| Spring Boot 4.x | REST API framework |
| Apache PDFBox 2.x | PDF text extraction |
| Apache POI 5.x | DOCX / PPTX text extraction |
| OpenAI API | GPT-4o Mini question generation |
| Jackson | JSON parsing |
| Docker | Containerization |
| Render | Cloud deployment |

---

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Maven
- OpenAI API key

### Run Locally

```bash
# Clone the repo
git clone https://github.com/lakshaygaba18/LastMinuteExam-Backend.git
cd LastMinuteExam-Backend

# Set your OpenAI API key
export OPENAI_API_KEY=your_key_here

# Build and run
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`

### Environment Variables

| Variable | Description | Required |
|---|---|---|
| `OPENAI_API_KEY` | Your OpenAI API key | Yes |

### Docker

```bash
docker build -t lastminuteexam-backend .
docker run -p 8080:8080 -e OPENAI_API_KEY=your_key lastminuteexam-backend
```

---

## 🧠 How The AI Prompt Works

The backend sends a carefully engineered prompt to GPT-4o Mini that instructs it to:

1. Generate questions **only from the actual document content** — no hallucination
2. Vary answer format based on question type (bullets for lists, paragraphs for explanations, comparisons for differences)
3. Scale question count dynamically based on document length
4. Return **strict JSON** — no markdown, no preamble, no extra text
5. Include formulas, dates and key facts in cheat sheet entries

If GPT returns malformed JSON, the backend automatically falls back to keyword-based rule generation so the app never crashes.

---

## 📊 Question Count by Document Size

| Pages | Viva | 1 Mark | 3 Mark | 5 Mark | 10 Mark |
|---|---|---|---|---|---|
| < 10 | 15 | 10 | 8 | 5 | 2 |
| 10 – 25 | 25 | 15 | 12 | 8 | 5 |
| 26 – 50 | 30 | 20 | 15 | 10 | 5 |
| 100+ | 50 | 30 | 20 | 15 | 10 |

---

## 👨‍💻 Author

**Lakshay Gaba**
- GitHub: [@lakshaygaba18](https://github.com/lakshaygaba18)
- Built during B.Tech — designed, developed and deployed solo

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Built with ❤️ for every student who has an exam tomorrow
</div>
