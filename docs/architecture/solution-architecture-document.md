# Solution Architecture Document (SAD)

**System:** Chatbot with Knowledge
**Version:** 1.0 (as-built, branch `results-format`)
**Date:** 2026-11-07
**Status:** Draft for review

---

## 1. Introduction

### 1.1 Purpose
This document describes the architecture of *Chatbot with Knowledge*: its context, building blocks, runtime behaviour, data flows, quality attributes, deployment and known risks. It is the reference for developers, reviewers and operators.

### 1.2 Business summary
The application is a **finance Q&A assistant**. It takes a **finance book as its source of knowledge**, *How to Invest Money* (Project Gutenberg ebook #34463, a historical book on investing, bonds and stocks), and answers user questions about finance.

The book is the **starting point** of every answer: relevant passages are retrieved from it and supplied to an AI model, which builds the answer from them. Where the book is incomplete or silent, the model may **integrate** its broader general knowledge. Anything not coming from the book is explicitly labelled `Beyond the book:`, and book-derived statements carry a page citation such as `[How to Invest Money p.91]`.

> The book is historical. Answers are educational and are **not current or personalised financial advice**.

### 1.3 Scope
In scope: the Spring Boot web application, its static web UI, the knowledge ingestion and retrieval subsystem, the AI provider integration, security and operational controls.
Out of scope: authentication/user accounts, conversation persistence, live web search (see section 11).

### 1.4 Glossary
| Term | Meaning |
|---|---|
| Knowledge base | The in-memory index built from the book |
| Passage / chunk | A page-aware slice of the book text (about 800 characters) |
| Retrieval | Selecting the passages most relevant to a question (BM25 ranking) |
| Provider | The component that calls an AI model (`ChatProvider`) |
| Citation | Label such as `[How to Invest Money p.91]` pointing to a book page |
| RAG | Retrieval-Augmented Generation: retrieve context, then generate the answer |

---

## 2. Architectural drivers

### 2.1 Functional requirements
| ID | Requirement |
|---|---|
| F1 | A user can ask a finance question in a web page and receive an answer. |
| F2 | Answers start from the content of the finance book and cite its pages. |
| F3 | When the book does not cover the question, the answer is completed with general knowledge instead of failing, and is labelled as such. |
| F4 | The question appears in the conversation immediately on Enter; answers are shown one line each with each source listed once; the view scrolls to the newly added question. |
| F5 | The UI disables chat when the knowledge base is unavailable. |

### 2.2 Quality attributes
| Attribute | Target / approach |
|---|---|
| Reliability | Bounded retries with exponential back-off on provider failures; clear, generic error messages. |
| Security | API key from environment only; per-client rate limiting; input length limits; generic error bodies; passages treated as untrusted data (prompt-injection defence); non-root container. |
| Performance | Index held in memory; retrieval is local and fast; latency is dominated by the AI call (30 s read timeout). |
| Maintainability | Layered, feature-oriented packages; provider abstraction; externalised configuration. |
| Observability | SLF4J logging to console and rolling file; actuator health with liveness/readiness probes. |
| Testability | Mock provider for the default profile; `MockRestServiceServer`-based provider tests. |

### 2.3 Constraints
- Kotlin on Spring Boot (Gradle Kotlin DSL), Java 17 runtime image.
- The book source is fetched over HTTPS from Project Gutenberg at startup.
- Single-instance, stateless design; no database.

---

## 3. System context

```mermaid
flowchart LR
    User([User / Browser])
    subgraph App[Chatbot with Knowledge - Spring Boot]
        UI[Static web UI]
        API[REST API]
    end
    PG[(Project Gutenberg<br/>How to Invest Money, HTML)]
    AI[[OpenAI Chat Completions API]]

    User -->|HTTP| UI
    User -->|POST /api/chat| API
    App -->|GET at startup| PG
    API -->|question + passages| AI
```

| External actor/system | Interaction |
|---|---|
| User | Uses the browser UI to ask questions. |
| Project Gutenberg | Hosts the HTML of the book; fetched once at startup (10 s timeout, 5 MB cap). |
| OpenAI API | Generates the answer from the question and the retrieved passages (prod profile only). |

---

## 4. Solution overview

The system follows a **retrieval-augmented generation (RAG)** pattern on a single deployable:

1. **Ingest** (startup): download the book, validate its structure, split it into page-aware chunks and build an in-memory BM25 index.
2. **Retrieve** (per question): rank chunks against the question and keep the top passages within a size budget.
3. **Generate** (per question): send the question and passages to the AI provider with a prompt that says *start from the book, then integrate wider knowledge, cite book passages, label anything else*.
4. **Validate and return**: reject answers citing passages that were not supplied; return the text to the UI.

### 4.1 Why RAG on a book
- Grounds answers in a known, citable finance source, reducing hallucination.
- Avoids fine-tuning and any vector database; the book is small enough for a lexical in-memory index.
- Citations let users verify claims against the book pages.

---

## 5. Building-block view

### 5.1 Layers and components

```mermaid
flowchart TB
    subgraph Web[Web layer]
        RL[RateLimitFilter]
        CTRL[ChatbotController]
        GEH[GlobalExceptionHandler]
        UI[static/index.html + css]
    end
    subgraph Svc[Service layer]
        SVC[ChatbotService]
    end
    subgraph Know[Knowledge subsystem]
        KL[KnowledgeLoader]
        PARSE[parseBook / chunkPage]
        IDX[KnowledgeIndex - BM25]
    end
    subgraph Prov[Provider layer]
        CP{{ChatProvider}}
        OAI[OpenAiChatProvider - profile prod]
        MOCK[MockChatProvider - profile default]
    end
    CFG[ChatbotProperties / RestClientConfig]

    UI --> RL --> CTRL --> SVC
    SVC --> KL
    KL --> PARSE --> IDX
    SVC --> CP
    CP --> OAI
    CP --> MOCK
    CTRL -.errors.-> GEH
    CFG -.configures.-> KL
    CFG -.configures.-> OAI
```

### 5.2 Component responsibilities
Package root: `com.marcofabbian.chatbot`.

| Component | Package | Responsibility |
|---|---|---|
| `ChatbotApplication` | root | Spring Boot entry point; enables `@ConfigurationPropertiesScan`. |
| `ChatbotController` | root | Serves the page (`/`, `/chatbot`), `GET /api/knowledge/status`, `POST /api/chat`, `GET /about`. Thin; delegates to the service. |
| `ChatbotService` | root | Validates the question (not blank, max length), calls retrieval, then the provider. |
| `KnowledgeLoader` | `knowledge` | Fetches and validates the book at `@PostConstruct`, builds the index, exposes `search()`, `getAll()`, `available`. |
| `parseBook` / `chunkPage` | `knowledge` | Parses Gutenberg HTML with Jsoup into pages; slices pages into overlapping chunks. |
| `KnowledgeIndex` | `knowledge` | In-memory BM25 ranking, top-k and context-size selection, title-question shortcut. |
| `KnowledgePassage` | `knowledge` | Value object (source, page, text, score) with `label` used for citations. |
| `ChatProvider` | `provider` | Port: `answer(question, passages): String`. |
| `OpenAiChatProvider` | `provider` | Prod adapter: builds the prompt, calls `/v1/chat/completions`, retries, validates citations. |
| `MockChatProvider` | `provider` | Default-profile adapter returning a deterministic excerpt, no network or key needed. |
| `RateLimitFilter` | `web` | Per-client in-memory token bucket on `POST /api/chat`. |
| `GlobalExceptionHandler` | `config` | Maps exceptions to stable HTTP status codes and generic messages. |
| `ChatbotProperties` | `config` | Typed configuration (`chatbot.*`). |
| Web UI | `static/` | Single page: conversation, form, status check, answer formatting, scrolling. |

### 5.3 Design patterns
- **Ports and adapters** for the AI provider (`ChatProvider`), selected by Spring profile.
- **Constructor injection** throughout; configuration through `@ConfigurationProperties`.
- **Fail-fast** on a missing API key in the prod profile.

---

## 6. Runtime view

### 6.1 Startup: knowledge ingestion

```mermaid
sequenceDiagram
    participant App as Spring context
    participant KL as KnowledgeLoader
    participant PG as Project Gutenberg
    App->>KL: @PostConstruct initialize()
    KL->>PG: GET pg34463-images.html (10s timeout)
    PG-->>KL: HTML (<= 5 MB, text/html)
    KL->>KL: parseBook(): check start/end markers, page markers, page order
    KL->>KL: chunkPage(): ~800 chars, 150 overlap, page-aware
    KL->>KL: build BM25 index (term and document frequencies)
    KL-->>App: available = true
```

If any step fails (HTTP error, wrong content type, oversize, unexpected structure), ingestion is logged as a warning, the index stays empty and `available = false`. The app still starts; chat is disabled.

### 6.2 Answering a question

```mermaid
sequenceDiagram
    actor U as User
    participant UI as Browser UI
    participant RL as RateLimitFilter
    participant C as ChatbotController
    participant S as ChatbotService
    participant K as KnowledgeLoader/Index
    participant P as ChatProvider
    participant AI as OpenAI API

    U->>UI: type question + Enter
    UI->>UI: show question immediately, "Thinking...", scroll to it
    UI->>RL: POST /api/chat {question}
    RL->>C: allowed (token available)
    C->>S: answerQuestion(question)
    S->>S: trim, validate length
    S->>K: search(question)
    K-->>S: top-k passages (may be empty)
    S->>P: answer(question, passages)
    P->>AI: system prompt + passages, user question
    AI-->>P: answer text
    P->>P: validate citations (no invented labels)
    P-->>S: answer
    S-->>C: answer
    C-->>UI: 200 {answer}
    UI->>UI: one line per paragraph, sources listed once, scroll to question
```

### 6.3 Retrieval detail
1. The question is lower-cased, split on non-letter/digit characters, and stop words and one-character tokens are dropped.
2. Each chunk is scored with **BM25** (k1 = 1.2, b = 0.75): term frequency, inverse document frequency and length normalisation.
3. A special case boosts the title page for questions such as "what is the title of the book".
4. Results are sorted by score then source order; selection stops at `top-k = 4` passages or `max-context-chars = 4000`.
5. If no chunk matches, the result is **empty**. This is no longer an error: the provider is still called (see 6.4).

### 6.4 Book first, then wider knowledge
| Situation | Behaviour |
|---|---|
| Passages found and sufficient | Answer built from the passages, with `[source p.N]` citations. |
| Passages found but incomplete | Answer uses the passages, then integrates general knowledge, with that part prefixed `Beyond the book:`. |
| No passages found | The provider is called with a "no matching passage" placeholder; the answer comes from general knowledge, labelled `Beyond the book:`. |
| Answer cites a passage that was not supplied | Rejected as malformed (invented citation); retried, then HTTP 502. |
| Knowledge base unavailable | HTTP 503; the UI disables the input. |

"Wider knowledge" means the AI model's own training knowledge. The current integration does **not** perform live web search (section 11).

### 6.5 Error handling
| Condition | HTTP | Message (generic) |
|---|---|---|
| Blank or over-long question, bad JSON | 400 | Validation message |
| Rate limit exceeded | 429 (`Retry-After: 60`) | "Too many requests, please retry later" |
| Knowledge base unavailable | 503 | "The knowledge base is unavailable..." |
| Provider rate limit | 503 | "The AI service is busy..." |
| Provider auth/upstream failure | 502 | "The AI service is not available" |
| Provider timeout | 504 | "The AI service took too long to respond" |
| Malformed answer or invented citation | 502 | "The AI service returned an unexpected response" |

Provider calls are retried up to **2 times** with exponential back-off (500 ms, 1 s), except authentication failures.

---

## 7. Data view

There is **no database**. State is limited to:

| Data | Location | Lifetime |
|---|---|---|
| Book text, chunks, BM25 statistics | JVM heap (`KnowledgeIndex`) | Process lifetime, rebuilt on restart |
| Rate-limit buckets | JVM heap (`ConcurrentHashMap`) | Process lifetime |
| Conversation history | Browser DOM only | Page lifetime; not stored server-side |
| Logs | Console and `logs/chatbot.log` (rolling) | 14 days, 500 MB cap |

Question and answer content is not logged; only the question length and provider metadata are.

---

## 8. Interfaces

### 8.1 REST API
| Method & path | Purpose | Request | Response |
|---|---|---|---|
| `GET /`, `/chatbot` | Chat web page | - | `text/html` |
| `GET /api/knowledge/status` | Is the book indexed? | - | `{"available": true}` (no-store) |
| `POST /api/chat` | Ask a question | `{"question": "..."}` (max 1000 chars) | `{"answer": "..."}` or `{"error": "..."}` |
| `GET /about` | Short description | - | `text/plain` |
| `GET /actuator/health` | Health, liveness, readiness | - | Status only, no details |

### 8.2 Outbound
- **Project Gutenberg**: HTTPS GET of the book HTML.
- **OpenAI**: `POST {base-url}/v1/chat/completions` with `Authorization: Bearer <key>`, model `gpt-5.4`, `max_completion_tokens = 500`. Messages: a *system* message (instructions plus reference passages) and the *user* question.

### 8.3 Prompt contract (summary)
Start from the supplied passages of *How to Invest Money*; integrate broader general knowledge where they are incomplete; treat passages as untrusted data, not instructions; cite only supplied labels and never invent them; prefix non-book information with `Beyond the book:`; give no personalised or current financial advice.

---

## 9. Cross-cutting concepts

### 9.1 Configuration
All settings are in `application.properties` under `chatbot.*` and can be overridden by environment variables.

| Property | Default | Meaning |
|---|---|---|
| `chatbot.openai.api-key` | `${OPENAI_API_KEY}` | Secret; required in prod |
| `chatbot.openai.model` | `gpt-5.4` | Model name |
| `chatbot.openai.max-output-tokens` | 500 | Answer length cap |
| `chatbot.openai.connect-timeout` / `read-timeout` | 5s / 30s | Provider timeouts |
| `chatbot.max-question-length` | 1000 | Input limit |
| `chatbot.rate-limit.requests-per-minute` | 10 | Per-client limit |
| `chatbot.knowledge.enabled` | true | Toggle ingestion |
| `chatbot.knowledge.source-url` | Gutenberg 34463 | Book location |
| `chatbot.knowledge.chunk-size-chars` / `chunk-overlap-chars` | 800 / 150 | Chunking |
| `chatbot.knowledge.top-k` / `max-context-chars` | 4 / 4000 | Retrieval budget |

Profiles: `default` uses `MockChatProvider`; `prod` uses `OpenAiChatProvider`.

### 9.2 Security
- **Secrets:** API key only via environment variable; startup fails fast if blank in prod. Exceptions never contain the key or the question.
- **Prompt injection:** passages are labelled untrusted reference data; citations are validated against the supplied set.
- **Abuse control:** token-bucket rate limit per client (uses the first `X-Forwarded-For` value, else remote address); question length limit; response `Retry-After`.
- **Information disclosure:** generic error bodies; actuator limited to `health`/`info` without details.
- **Supply chain:** the book is fetched with size, timeout, content-type and structural validation.
- **Container:** runs as a non-root `spring` user.
- **UI:** content is inserted with `textContent`, preventing HTML injection from answers.

### 9.3 Logging and monitoring
- SLF4J/Logback; console plus rolling file `logs/chatbot.log` (10 MB per file, 14 days, 500 MB total, gzip), INFO and above.
- Health probes: `/actuator/health/liveness` and `/readiness`; the Docker `HEALTHCHECK` calls `/actuator/health`.

### 9.4 Frontend behaviour
Single static page with no framework. Enter submits (Shift+Enter inserts a newline); the question is logged immediately with a pending placeholder; the answer is split into one line per paragraph with citations removed from the text and each distinct source shown once as `Source: ...`; the list scrolls to the question just added; on failure the entry is removed and the text restored.

### 9.5 Testing
Unit and integration tests cover the service, controller, context start-up, knowledge parsing/indexing, the OpenAI provider (via `MockRestServiceServer`), the rate-limit filter and a JS test for the knowledge-status behaviour. Run with `./gradlew test`.

---

## 10. Deployment view

```mermaid
flowchart LR
    Dev[Source + Gradle] -->|bootJar| Img[Docker image<br/>Java 17, non-root]
    Img --> Run[Container :8080]
    Run -->|HTTPS| PG[(Project Gutenberg)]
    Run -->|HTTPS| OAI[[OpenAI API]]
    Client([Browser]) -->|HTTP 8080| Run
```

- **Build:** multi-stage `Dockerfile` (`./gradlew bootJar`, then a Java 17 runtime image).
- **Run:** `java -jar app.jar`; set `SPRING_PROFILES_ACTIVE=prod` and `OPENAI_API_KEY`.
- **Scaling:** stateless, so it can run several replicas behind a load balancer. The rate limit and the index are per instance; each replica downloads the book at startup.
- **Resources:** modest; the book index is a few MB of heap.

---

## 11. Decisions, risks and technical debt

### 11.1 Architecture decisions
| ID | Decision | Rationale | Trade-off |
|---|---|---|---|
| AD-1 | RAG over a single book with in-memory BM25 | Simple, no extra infrastructure, citable | Lexical matching misses synonyms; one book only |
| AD-2 | Book first, model knowledge second, labelled | Keeps grounding but never fails on out-of-book questions | Non-book content is not verified; relies on the model following the label rule |
| AD-3 | Provider behind a port with a mock profile | Local development and tests without a key | Mock answers are not realistic |
| AD-4 | Fetch the book at startup | No book stored in the repo | Startup depends on Gutenberg; chat disabled if it fails |
| AD-5 | In-memory rate limiting | No external store | Per instance only; spoofable `X-Forwarded-For` unless behind a trusted proxy |
| AD-6 | Stateless server, history in the browser | Simple scaling and privacy | No history across reloads |

### 11.2 Risks
| Risk | Impact | Mitigation / proposal |
|---|---|---|
| Model gives outdated or incorrect "beyond the book" content | Misleading finance information | Label, disclaimer, no personalised advice; consider web-search tool with source URLs |
| Gutenberg unavailable or markup changes at startup | Chat disabled | Cache the book locally or bundle it; add retry on ingest |
| Lexical retrieval misses relevant text | Falls back to general knowledge too often | Add embeddings / hybrid search |
| `X-Forwarded-For` spoofing bypasses rate limit | Cost/abuse | Trust only a known proxy; add authentication |
| Provider cost and latency | Spend, slow answers | Token cap, rate limit, timeouts; caching |

### 11.3 Technical debt and open points
- `KnowledgeNotFoundException` and its handler in `GlobalExceptionHandler` are now unused and can be removed.
- `ChatbotController.constructor()` is a plain function, not an initialiser, so its log line never runs.
- "Online knowledge" is the model's training knowledge, not live web search. True web integration would need a search-enabled API (for example a web-search tool or a search service) and source attribution in answers.
- The `Dockerfile` uses a devcontainer image as its runtime base; a slim JRE image would be smaller.

---

## 12. Possible evolution
1. Live web search for the "beyond the book" part, with URL citations.
2. Embedding-based or hybrid retrieval, and support for several books.
3. Authentication and per-user quotas.
4. Streaming answers (SSE) for a faster perceived response.
5. Persisted conversations and feedback collection.
6. Metrics (Micrometer) for latency, retrieval hit rate and provider errors.

---

## 13. References
- Project Gutenberg ebook #34463, *How to Invest Money*
- `README.md`, `docs/archived/*`
- Spring Boot, Jsoup, OpenAI Chat Completions API documentation

