# Add Local Data Knowledge

## Goal

Ground the chatbot's answers in *How to Invest Money* by George Garr Henry (1908). Fetch and ingest its Project Gutenberg HTML at startup. If knowledge is unavailable, block questions; if the book cannot answer a question, return an error rather than a generic answer.

## Context

- The source is [Project Gutenberg eBook 34463](https://www.gutenberg.org/cache/epub/34463/pg34463-images.html), an historical book on investment principles, bonds, stocks and market movements. It is not current financial advice.
- The book was published in 1908 and is in the public domain in the United States; status elsewhere may differ. Project Gutenberg terms apply when redistributing its eBook. The application fetches the source and does not bundle it.
- The production provider uses OpenAI; other profiles use a deterministic mock. Keep existing input validation, rate limiting, OpenAI limits, retry policy and successful API response shape.

## Functional Requirements

1. Fetch the configured HTML once per startup and construct a complete in-memory index before accepting requests. An isolated HTTP client must not send the OpenAI authorization header. Do not fetch at request time or reload in the background.
2. Verify the source is the expected book; exclude its contents, transcriber's notes and Gutenberg boilerplate. Extract page numbers from `span.pagenum` anchors, including those inside paragraphs. Split pages into bounded, overlapping chunks with page metadata.
3. Rank chunks using deterministic BM25 tokenization; limit the number and total text length of selected passages. Do not use embeddings or an external search service.
4. Before calling the provider, reject requests if knowledge is unavailable or no passage matches. Ask the model to answer only from supplied passages, treat them as reference data rather than instructions, cite supplied labels such as `[How to Invest Money p.91]`, and return `[NOT_IN_KNOWLEDGE]` if they cannot answer.
5. If the model refuses, return HTTP 422 with an error message. Never return a generic answer. Reject uncited answers and fabricated page labels as malformed provider responses.
6. Expose `GET /api/knowledge/status` with `{"available":true}` or `{"available":false}`. Disable the question controls while status is unknown or unavailable, and show an accessible error. The API independently enforces the same gate.
7. Fetch or parsing failures and `chatbot.knowledge.enabled=false` leave the application running but make knowledge unavailable; chat returns HTTP 503. A restart is required to fetch again. An unsupported question returns HTTP 422 but does not prevent later questions.
8. The mock provider returns a clearly labeled excerpt of a retrieved passage and its citation, not a purported evaluated answer. It requires the same knowledge availability and retrieval as production.
9. Log ingestion counts and safe failure metadata, not question text, passage text, source response bodies or credentials.

## Configuration

Settings in `application.properties`, validated through `ChatbotProperties`:

| Property | Default | Constraint |
|---|---|---|
| `chatbot.knowledge.enabled` | `true` | `false` blocks chat |
| `chatbot.knowledge.source-url` | `https://www.gutenberg.org/cache/epub/34463/pg34463-images.html` | HTTPS URL without user info or fragment |
| `chatbot.knowledge.source-name` | `How to Invest Money` | Nonblank; no brackets or line breaks |
| `chatbot.knowledge.fetch-timeout` | `10s` | 1–60 seconds for connect and read |
| `chatbot.knowledge.chunk-size-chars` | `800` | 200–4000 |
| `chatbot.knowledge.chunk-overlap-chars` | `150` | Nonnegative and smaller than chunk size |
| `chatbot.knowledge.top-k` | `4` | 1–20 |
| `chatbot.knowledge.max-context-chars` | `4000` | 500–16000 and at least chunk size |

## Implementation Notes

- Use jsoup for HTML parsing and a separate Spring `RestClient` builder with bounded timeouts and a 5 MiB response limit.
- Build the index during bean initialization and retain it in memory. Validate the Gutenberg book boundaries and required pages before making it available.
- The service validates the question, retrieves passages, and invokes the selected provider. Existing OpenAI retries and errors are retained.
- A model's assessment and citation checks do not prove that a response is factually supported; lexical retrieval can miss relevant passages. Clearly document these limitations.

## Error Handling

- Unavailable or disabled knowledge: HTTP 503, JSON `error`, and no provider call.
- No matching passages or model refusal: HTTP 422, JSON `error`; no generic answer.
- Uncited or fabricated citations: provider malformed-response error, HTTP 502 after the existing retry policy.
- Fetch failure, wrong content type, oversized or structurally invalid source: log safe diagnostics, keep the app running, and block chat.
- Invalid configuration: fail startup with validation details. Other provider errors retain the existing mappings.

## Testing Requirements

- Use handwritten HTML fixtures and mock HTTP servers by default; no real OpenAI calls.
- Test inline page markers, book boundaries, excluded boilerplate, chunk sizes and overlap, deterministic ranking, `top-k` and context limits.
- Test startup ingestion, invalid configuration, disabled mode, timeouts, HTTP/content/parse failures, no authorization header on the knowledge fetch, and unavailable API/UI behavior.
- Test no-match and model-refusal 422 responses, valid citations, missing or invented citations, provider retry behavior, mock excerpts and existing validation/rate limits.
- An explicitly enabled live-source check verifies the real Gutenberg HTML and investment queries; it is not part of the default offline tests.

## Documentation Requirements

- Document the source, historical nature, citation format, configuration, startup network dependency, 503/422 behavior, recovery, mock limitations and licensing in the README.
- Keep configuration comments accurate. Note the limitations of lexical retrieval and model-generated answers.

## Acceptance Criteria

1. With knowledge enabled and a successful fetch, the book is indexed before the first chat request; a question about municipal bonds retrieves a passage from its chapter.
2. Answers accepted from the production provider cite supplied book pages; a model refusal or zero matches returns HTTP 422 without a generic answer.
3. If ingestion fails or knowledge is disabled, status is unavailable, the browser blocks submission, and direct chat requests return HTTP 503 without calling the provider.
4. Prompt context respects configured limits; logs contain no question, passage, source body or credential.
5. Offline tests and the Gradle build pass; the live-source compatibility check is opt-in.

## Out of Scope

- Additional books, PDF ingestion, OCR, file uploads, editing, runtime reload, and vendored copies.
- Embeddings, vector databases, hosted file search, conversation memory and streaming.
- Legal advice, investment advice, deployment infrastructure, and changes to rate limits.
