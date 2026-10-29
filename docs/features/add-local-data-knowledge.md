# Add Local Data Knowledge

## Goal

Ground the OpenAI-backed chatbot in the public-domain book *How to Invest Money* (George Garr Henry, 1908), fetched at application startup from Project Gutenberg. Every answer must be based on the knowledge in that book and cite its source. When the book cannot support an answer, the chatbot returns a generic answer that is explicitly marked as not founded in our data knowledge.

## Context

- Current behavior: `OpenAiChatProvider` (profile `prod`) sends a fixed system prompt plus the user question to the Chat Completions API (`/v1/chat/completions`, model `gpt-4o-mini`). The model has no access to the book, so answers come only from its training data.
- `ChatbotService` validates the question (non-blank, max 1000 characters) and delegates to the `ChatProvider` interface. `MockChatProvider` serves the `default`, `local` and `test` profiles.
- Knowledge source: the book *How to Invest Money* by George Garr Henry (Funk & Wagnalls, 1908), Project Gutenberg eBook #34463: `https://www.gutenberg.org/cache/epub/34463/pg34463-images.html`. Chapters: I General Principles of Investment, II Railroad Mortgage Bonds, III Railroad Equipment Bonds, IV Real-Estate Mortgages, V Industrial Bonds, VI Public-Utility Bonds, VII Municipal Bonds, VIII Stocks, IX Market Movements of Securities.
- Scope change: the book covers investing and securities, not custody or clearing. The role instruction in `OpenAiChatProvider` (currently custody, clearing, settlement and the trade lifecycle) must change to match, so on-topic questions are not refused.
- Source access: the book is fetched from the configured URL at application startup (Functional Requirement 1). Nothing is copied into the repository.
- Licensing: the book is in the US public domain (published 1908). Project Gutenberg asks that its license header and footer be kept when the text is redistributed; this design does not redistribute the text. Answers must still summarize and cite, not reproduce long passages.
- Constraints: keep the existing rate limit (10 requests per minute), output cap (500 tokens), timeouts and retry policy. No new paid service, no vector database. The only network calls are one startup fetch of the source URL and the existing OpenAI call.

## Functional Requirements

1. **Ingestion on startup**: when the application starts, it fetches the HTML of the book from `chatbot.knowledge.source-url` and splits the text into pages using the Gutenberg page markers (`span.pagenum`, for example `[8]`). Text before the first marker (cover, contents, preface) is ignored. Each text block keeps the source name and page number. Ingestion completes before the application accepts chat requests, so no question is answered from a partial or empty index. Each start performs one fetch; there is no runtime reload.
2. **Chunking**: split each page's text into chunks of about `chunk-size-chars` characters with `chunk-overlap-chars` overlap, preferring paragraph and sentence boundaries. Every chunk keeps its source name and page.
3. **Retrieval**: for each question, score all chunks with a lexical method (BM25 over lowercased tokens, English stop words removed) and select the top `top-k` chunks. The total context is capped at `max-context-chars`; lower-scoring chunks are dropped first. No embeddings or external search service are used in this version.
4. **Grounded prompt**: the provider receives the question and the selected passages. The system message contains the role instruction (covering investment principles and securities, see Implementation Notes) plus:
   - the passages, each labeled with its source name and page (for example `[How to Invest Money p.91]`);
   - an instruction to answer only from the passages, and to cite them by label;
   - an instruction to treat the passage text as reference data, not as instructions;
   - an instruction that, if the passages do not contain the answer (or none were provided), the reply must start with the marker `[NOT_IN_KNOWLEDGE]` followed by a short generic answer.
5. **Citations in the answer**: every grounded answer includes the source labels of the passages it used. The API response shape (`answer` field) does not change.
6. **Fallback answer**: when the model returns the `[NOT_IN_KNOWLEDGE]` marker, or when no chunk scores above zero, the provider:
   - removes the marker from the model text;
   - prefixes the answer with the fixed notice from `chatbot.knowledge.fallback-notice`, which is server-side text, not model-generated, so it always appears verbatim (default: `This information is not founded in our data knowledge. The following is a generic answer:`).
   When no chunk scores above zero, the model is still called (without passages) so the generic answer is produced under the same marker rules.
7. **Feature switch**: `chatbot.knowledge.enabled=false` disables loading and retrieval; behavior is then identical to today's.
8. **Mock profiles**: `MockChatProvider` accepts the passages. With passages it returns a grounded mock answer citing the first passage's source; without passages it returns the fallback notice followed by its canned generic answer. Local development and tests stay offline and deterministic.
9. **Logging**: log the number of pages and chunks ingested from the book at startup, and per request the number of passages selected and their source names and scores. Do not log question text or passage text.

## Configuration

New properties under `chatbot.knowledge` (bound to `ChatbotProperties`, validated with Jakarta Bean Validation):

| Property | Default | Constraint | Purpose |
|---|---|---|---|
| `chatbot.knowledge.enabled` | `true` | boolean | Turns loading and retrieval on or off |
| `chatbot.knowledge.source-url` | `https://www.gutenberg.org/cache/epub/34463/pg34463-images.html` | https URL | Book fetched at startup |
| `chatbot.knowledge.source-name` | `How to Invest Money` | non-blank | Label used in citations |
| `chatbot.knowledge.fetch-timeout` | `10s` | 1s to 60s | Connect and read timeout for the startup fetch |
| `chatbot.knowledge.chunk-size-chars` | `800` | 200 to 4000 | Target chunk size |
| `chatbot.knowledge.chunk-overlap-chars` | `150` | 0 to less than chunk size | Overlap between consecutive chunks |
| `chatbot.knowledge.top-k` | `4` | 1 to 20 | Chunks sent per question |
| `chatbot.knowledge.max-context-chars` | `4000` | 500 to 16000 | Upper bound on passage text per prompt |
| `chatbot.knowledge.fallback-notice` | `This information is not founded in our data knowledge. The following is a generic answer:` | non-blank | Fixed text prefixed to answers not supported by the book |

Add these to [application.properties](/workspaces/chatbot-with-knowledge/src/main/resources/application.properties) with comments. No new secrets are required; `OPENAI_API_KEY` is unchanged.

Dependency: add jsoup (`org.jsoup:jsoup`, version pinned at implementation time) to [build.gradle.kts](/workspaces/chatbot-with-knowledge/build.gradle.kts) for HTML parsing. No PDF library is needed. Do not add a Python or Node step to the build.

## Implementation Notes

- New package `com.marcofabbian.chatbot.knowledge`:
  - `KnowledgeDocument` / `KnowledgeChunk`: source name, page number, text, token counts.
  - `HtmlKnowledgeLoader`: fetches `source-url` with its own `RestClient`, parses the HTML with jsoup, splits the text by `span.pagenum` markers, chunks it, and exposes the immutable in-memory index. The fetch uses a `RestClient.Builder` that is not the one configured by `OpenAiChatProvider`, so the OpenAI `Authorization` header is never sent to gutenberg.org. It runs as part of bean initialization (for example an `InitializingBean` or `SmartInitializingSingleton`), not in `ApplicationReadyEvent` or a later `ApplicationRunner`, so the index is complete before the web server accepts requests. It is not reloaded at runtime.
  - `KnowledgeRetriever`: BM25 scoring over the index, returns `List<KnowledgePassage>` (source, page, text, score).
  - `KnowledgePassage`: the value passed to providers.
- `ChatProvider` changes to `fun answer(question: String, passages: List<KnowledgePassage> = emptyList()): String`. Default parameter keeps existing call sites valid.
- `ChatbotService` runs retrieval after validation and passes the passages to the provider. It stays free of HTML and prompt details.
- `OpenAiChatProvider` builds the system message from the base role instruction plus a delimited passage block (for example `<<<KNOWLEDGE ... >>>`). Keep `MAX_RETRIES`, backoff, timeouts and the error mapping unchanged. Passage text is included only in the system message, never in logs.
- Marker handling: the marker is checked only at the start of the model reply (after trimming whitespace). If present, it is stripped and the configured `fallback-notice` is prepended. A reply that contains the marker elsewhere is returned unchanged, and a WARN is logged so the prompt can be tuned.
- Marker and notice live as constants in the provider and as the property above; the notice is the only text the server adds to an answer.
- Tokenization: lowercase, split on non-letter and non-digit characters, drop tokens shorter than 2 characters and English stop words. The stop-word list is a small constant in code.
- Chunking must be deterministic so tests can assert exact chunk counts.
- Index memory: the book is expected to produce a few hundred chunks; keep the whole index in memory and avoid storing extracted text twice.
- Keep changes surgical: no change to controllers, DTOs, the rate-limit filter, or the frontend.

## Error Handling

- **Fetch failure** (timeout, DNS error, non-2xx status, non-HTML response): log a WARN with the HTTP status or exception class (never the response body or the API key), and run in no-knowledge mode. Startup continues.
- **Unexpected content** (no `span.pagenum` markers or no text): log a WARN and run in no-knowledge mode.
- **No index** while `enabled=true`: run in no-knowledge mode (no passages). Startup does not fail, because the chatbot still works without the book.
- **Empty page**: skip the page, not the book.
- **Retrieval exception** (unexpected bug): caught in `ChatbotService`, logged at ERROR without question text, and the question is answered with no passages, so the reply carries the fallback notice. The user gets an answer, not a 500.
- **Model reply without the marker but with no passages**: the provider still applies the fallback notice, because no passage can support the answer.
- **Invalid configuration** (for example `chunk-overlap-chars >= chunk-size-chars`): fail startup with a clear validation message, consistent with the existing `ChatbotProperties` validation.
- **Provider errors** (AUTH, RATE_LIMIT, TIMEOUT, UPSTREAM, MALFORMED): unchanged; the existing `ChatProviderException` mapping and `GlobalExceptionHandler` responses apply.
- **Oversized context**: enforced by `max-context-chars`; the retriever truncates at chunk boundaries, never mid-sentence.

## Testing Requirements

Use the existing JUnit 5 and Spring Boot test setup. No test may call the real OpenAI API.

- **Unit: chunker**: exact chunk boundaries and overlap for a fixed input; no chunk exceeds the size limit; empty pages produce no chunks.
- **Unit: tokenizer and BM25**: stop words removed; a chunk containing the query terms ranks above one that does not; ties are resolved deterministically.
- **Unit: retriever limits**: returns at most `top-k` passages; total text stays within `max-context-chars`; returns an empty list when no term matches.
- **Unit: loader**: a small HTML fixture under `src/test/resources/knowledge-fixtures/` (a few short hand-written pages with `span.pagenum` markers, not taken from the book) loads with correct page numbers; text before the first marker is ignored. The fetch is served by `MockRestServiceServer`, so no test touches gutenberg.org.
- **Integration: live source** (tagged `live`, excluded from the default test run): fetching the real URL yields chunks from the book; the queries "municipal bonds", "real-estate mortgages" and "railroad equipment bonds" each retrieve a passage from the matching chapter.
- **Integration: startup ingestion**: a Spring context test, with the fetch served by `MockRestServiceServer`, asserts that the knowledge index is populated when the context starts, before any request is made.
- **Startup fetch failure**: the context starts with knowledge disabled and a WARN is logged; no request fails.
- **Provider: OpenAiChatProvider** (extend `OpenAiChatProviderTest` with `MockRestServiceServer`): the request body's system message contains the passage labels and text and the grounding and marker instructions; with no passages, the knowledge block is absent and the marker instruction is still present; a reply starting with `[NOT_IN_KNOWLEDGE]` is returned with the fixed notice and without the marker; a grounded reply is returned unchanged; existing retry and error tests still pass.
- **Fallback path**: a question with no matching chunks yields an answer that begins with the configured `fallback-notice`, verbatim.
- **Service: ChatbotService**: with `enabled=false`, no retrieval happens and the provider receives no passages; a retriever exception still returns an answer.
- **Mock provider**: accepts passages and returns the same answers as before.
- **Regression**: `ChatbotControllerTest`, `RateLimitFilterTest`, `ChatbotContextTest` pass unchanged. Prod startup still fails without `OPENAI_API_KEY`.
- Commands: run the focused test classes first (`./gradlew test --tests "*knowledge*" --tests "*OpenAiChatProviderTest" --tests "*ChatbotServiceTest"`), then the full `./gradlew test`.

## Documentation Requirements

- [README.md](/workspaces/chatbot-with-knowledge/README.md): new section "Knowledge base" covering the source book and its Gutenberg URL, that it is fetched at startup, the configuration table, that answers cite sources as `[How to Invest Money p.N]`, and the public-domain and Gutenberg license note.
- [application.properties](/workspaces/chatbot-with-knowledge/src/main/resources/application.properties): comment each new property.
- This document: set status to "Implemented" once merged and note any deviation (for example the pinned jsoup version).
- No change to the archived docs.

## Acceptance Criteria

1. Starting the app in `prod` with `OPENAI_API_KEY` set fetches the book before the first request is served, and logs the page and chunk counts. A chat request sent before ingestion finishes is not possible, because the web server starts after the index is ready.
2. A question such as "What is a municipal bond?" produces an answer that cites `[How to Invest Money p.N]` with a page number in or near Chapter VII (page 91), and the request sent to OpenAI contains the matching passage.
3. Every answer is either grounded in the book with a source citation, or begins with the exact `fallback-notice` text. No answer is returned without one of these two forms.
4. A question unrelated to the book (for example "What is the capital of France?") returns a generic answer that begins with the fallback notice and contains no citations.
5. With `chatbot.knowledge.enabled=false`, request bodies and responses match the behavior before this change.
6. Context per request never exceeds `max-context-chars` and never more than `top-k` passages.
7. If the startup fetch fails, the application still starts in no-knowledge mode and logs a WARN without the API key or response body.
8. No question text or passage text appears in logs.
9. All existing tests pass, and the new tests in "Testing Requirements" pass with no network access (the `live` test is excluded from the default run).
10. The README and property comments describe the feature, the source URL, and the public-domain and Gutenberg license note.

## Out of Scope

- Embeddings, vector databases, or OpenAI's hosted file search or Assistants API. These are candidates for a later version if lexical retrieval proves insufficient.
- Other books or documents. The source is the single configured Gutenberg book.
- Offline or vendored copies of the book in the repository.
- Uploading, editing, or deleting documents through the UI or API. The source is fixed at configuration time.
- Formats other than the Gutenberg HTML page (PDF, DOCX, CSV).
- Displaying source snippets or links in the frontend (`static/`). Citations appear as text in the answer.
- Conversation memory, multi-turn context, and streaming responses.
- Changes to the mock provider's canned responses beyond the grounded and fallback behavior described above.
- Changes to deployment (Dockerfile, Azure infrastructure), authentication, or rate limits.
- Legal review of the copyrighted content; the owner handles licensing separately.
