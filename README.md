Notiva – Document Analysis & Field Extraction (Spring Boot)

This service turns uploaded PDFs into structured “records” using AWS Textract, then asks an LLM (via Spring AI) to extract the fields you care about with deterministic rules and confidence scoring.

What it does

Render PDFs → images (300 DPI) via PDFBox.

Analyze with Textract (TABLES + LAYOUT) to detect tables and free text.

Normalize to DocUnits

TEXT blocks outside tables (headers/footers treated separately).

TABLE blocks as 2D arrays; merged cells handled.

Extract fields with Spring AI

Deterministic prompt, no hallucinations (uses only records).

Monetary/date normalization rules.

Returns one JSON: list of {name, value, evidence, status, confidence, alternatives}.

Confidence from logprobs

Post-processes OpenAI token logprobs to compute calibrated confidence.

API

Base path: /api/analyze

1) POST /run-test (recommended for clients that upload files)

Consumes: multipart/form-data

Form fields

files (one or more PDF files)

fields (repeated form field, e.g. fields=totale&fields=data_fattura)

notes (optional string with high-priority instructions for the extractor)

cURL

curl -X POST http://localhost:8080/api/analyze/run-test \
  -F "files=@/path/doc1.pdf" \
  -F "files=@/path/doc2.pdf" \
  -F "fields=totale" \
  -F "fields=data_fattura" \
  -F "notes=Prefer EUR; ignore footer"


Response (JSON)

{
  "extractions": [
    {
      "name": "totale",
      "value": "€ 1.234,56",
      "evidence": { "snippet": "Totale fattura € 1.234,56" },
      "status": "OK",
      "confidence": 0.82,
      "alternatives": []
    },
    {
      "name": "data_fattura",
      "value": "25/06/2024",
      "evidence": { "snippet": "Data fattura: 25/06/2024" },
      "status": "OK",
      "confidence": 0.78,
      "alternatives": []
    }
  ]
}

2) POST /run

Body: a JSON RunAnalisisRequest.
Note: Spring cannot bind MultipartFile from JSON out-of-the-box. Use /run-test for file uploads. This endpoint is intended only if your client framework already binds multipart into RunAnalisisRequest.

Data model (key types)

RunAnalisisRequest

fields: List<String> — names of fields to extract

notes: String — optional extraction directives

files: List<MultipartFile> — PDFs to analyze

DocUnit (internal “record” fed to the LLM)

type: TEXT|TABLE

subtype: e.g. LINE, LAYOUT_TEXT, LAYOUT_TITLE… (for TEXT)

page: int

bbox: {left,top,width,height} (Textract coordinates)

text: String (for TEXT)

rows: List<List<String>> (for TABLE)

region: HEADER|BODY|FOOTER

origin: filename

ExtractionResult

extractions: List<ExtractionItem>

name, value, evidence.snippet, status(OK|AMBIGUO|NOT_FOUND), confidence, alternatives[]

How it decides

Tables: Builds row order, infers key vs value columns, concatenates continuation rows, and also detects inline Label: Value pairs inside cells.

Text outside tables: Collected only if not overlapping table areas (coverage threshold = 0.20).

Selection rules: label–field token overlap > inline Label:Value > substring matches.

Monetary normalization: formatted as € 12.345,67 (it-IT), multiple amounts separated by |.

Confidence: computed from token logprobs window; capped based on ambiguity/weak matches.

Requirements

Java: 17+

Build: Maven or Gradle

Libraries: Spring Boot, Spring AI, AWS SDK v2 (Textract), Apache PDFBox, Lombok

Configuration (what you must provide)

Do not commit secrets. Below are the names of required settings (no values).

AWS / Textract

AWS_ACCESS_KEY_ID

AWS_SECRET_ACCESS_KEY

AWS_REGION (Textract-supported region)

OpenAI via Spring AI

OPENAI_API_KEY

(optionally) Model selection (property key depends on your Spring AI version), e.g.:

spring.ai.openai.chat.options.model

Enable logprobs (so confidence can be computed), e.g.:

spring.ai.openai.chat.options.logprobs

spring.ai.openai.chat.options.topLogprobs

Use the property names supported by your Spring AI version; set them to request token log probabilities.

Server (optional)

server.port

CORS is currently * in AnalizeController (@CrossOrigin(origins = "*")).

Quick start

Maven

./mvnw -q -DskipTests spring-boot:run


Gradle

./gradlew -q bootRun


Then call the API (see cURL above).

Notes & limitations

/run-test is the straightforward way to send PDFs (multipart).

DPI is fixed at 300; adjust in PdfUtils if needed.

Textract costs per page — monitor your usage.

The extractor is deterministic by prompt design, but confidence depends on model logprobs; ensure logprobs are enabled.

Headers/footers are de-prioritized unless clearly relevant.

Project layout (key classes)

Controller

AnalizeController — /run (JSON), /run-test (multipart)

Services

TextractServiceImpl — PDF → images → Textract → DocUnit JSONL

FastExtractionService — Spring AI call + logprobs-based confidence

Utils

PdfUtils — PDF rendering

CreateDocUnitUtils — build LLM request payload

LogProbsUtils — confidence computations

Models

DocUnit, ExtractionResult, ExtractionItem, RunAnalisisRequest, Region, BBox, …

Troubleshooting

400/415 on upload → Use /run-test with multipart/form-data.

Null/low confidence → Ensure your model returns logprobs; set the appropriate Spring AI properties.

Parse JSON failed in FastExtractionService → The LLM must return only the specified JSON schema; check your notes and model settings.
