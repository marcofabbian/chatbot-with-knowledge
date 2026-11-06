# Chatbot with Knowledge

This project is a Kotlin Spring Boot chatbot that uses the historical book *How to Invest Money* to answer questions about investment principles, bonds, stocks and market movements. The book was published in 1908 and is not current financial advice.

The application can run with a mocked provider (default, for local development and tests) or with the real OpenAI chat provider (prod profile). The backend exposes a REST endpoint that accepts a question and returns the provider's answer.

## Running locally (mock provider)

1. Ensure Java 17 is installed and available on your PATH.
2. Start the application:
   `./gradlew bootRun`
3. Open http://localhost:8080 in a browser.

The mock provider is active in the `default`, `local` and `test` profiles and requires no credentials.
It returns a labeled excerpt from a matching passage, not an evaluated answer.
`SPRING_PROFILES_ACTIVE` selects the Spring profile; when unset, it defaults to `default`.

## Knowledge base

At startup the application fetches [*How to Invest Money* by George Garr Henry](https://www.gutenberg.org/cache/epub/34463/pg34463-images.html) from Project Gutenberg and indexes its text by page. Startup requires network access to the configured URL. The book is not bundled or refreshed while the application is running. If the fetch or parsing fails, or knowledge is disabled, chat is unavailable: the form is disabled and the API returns HTTP 503. Restore access or configuration and restart the app.

When no relevant passage is found, or the AI determines that the passages cannot answer, the API returns HTTP 422 rather than a generic answer. Questions such as "What is the title of the book?" retrieve its title passage on page 7. A successful production response cites one or more provided pages, for example `[How to Invest Money p.91]`. Lexical search can miss relevant information, and neither a model's judgment nor citation checks guarantee that its answer is supported or correct.

The book is in the public domain in the United States; copyright status can differ in other jurisdictions. Project Gutenberg's terms apply to redistribution of its eBook; review them before redistributing it.

## Running with the real AI provider

1. Set the API key as an environment variable (never in `application.properties` or committed files):
   `export OPENAI_API_KEY=sk-...`
2. Start with the prod profile:
   `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun`

The application fails at startup if the prod profile is active without `OPENAI_API_KEY`. Set a hard monthly spend limit on the key in the OpenAI dashboard.

## Running the container

The key is never baked into the image. `.env` is excluded from the build context by `.dockerignore`, and the key is injected at run time as an environment variable.

Local (`.env` at the repository root, unquoted `KEY=value` lines):

```bash
docker build -t chatbot-with-knowledge .
docker run --rm -p 8080:8080 --env-file .env -e SPRING_PROFILES_ACTIVE=prod chatbot-with-knowledge
curl http://localhost:8080/actuator/health
```

Azure Container Apps: store the key as a Container App secret (for example named `openai-api-key`) and map it to the environment variable `OPENAI_API_KEY` with `secretRef`, together with `SPRING_PROFILES_ACTIVE=prod`. The application reads the same variable name in both cases, so no code change is needed.

## Backend endpoint

POST `/api/chat`

Request body:
```json
{
  "question": "What are municipal bonds?"
}
```

Response:
```json
{
  "answer": "A grounded answer citing [How to Invest Money p.91]."
}
```

Successful answers differ by provider; the mock returns an excerpt instead. `GET /api/knowledge/status` returns `{"available":true}` when the index is ready. If the question is blank, the service returns HTTP 400 with a JSON error payload such as:
```json
{
  "error": "Question must not be blank"
}
```

## Configuration

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `chatbot.openai.api-key` | `OPENAI_API_KEY` | (required in prod) | OpenAI API key; never committed |
| `chatbot.openai.base-url` | `CHATBOT_OPENAI_BASE_URL` | `https://api.openai.com` | Provider base URL (https only) |
| `chatbot.openai.model` | `CHATBOT_OPENAI_MODEL` | `gpt-5.4` | Model used for answers |
| `chatbot.openai.max-output-tokens` | `CHATBOT_OPENAI_MAX_OUTPUT_TOKENS` | `500` | Caps completion tokens, including any reasoning tokens |
| `chatbot.openai.connect-timeout` | `CHATBOT_OPENAI_CONNECT_TIMEOUT` | `5s` | Connect timeout |
| `chatbot.openai.read-timeout` | `CHATBOT_OPENAI_READ_TIMEOUT` | `30s` | Read timeout |
| `chatbot.max-question-length` | `CHATBOT_MAX_QUESTION_LENGTH` | `1000` | Questions longer than this are rejected with 400 |
| `chatbot.rate-limit.requests-per-minute` | `CHATBOT_RATE_LIMIT_REQUESTS_PER_MINUTE` | `10` | Per-client limit on `POST /api/chat`; excess gets 429 with `Retry-After` |
| `chatbot.knowledge.enabled` | `CHATBOT_KNOWLEDGE_ENABLED` | `true` | When false, loading and chat are disabled |
| `chatbot.knowledge.source-url` | `CHATBOT_KNOWLEDGE_SOURCE_URL` | Gutenberg eBook 34463 URL | HTTPS source for the startup fetch |
| `chatbot.knowledge.source-name` | `CHATBOT_KNOWLEDGE_SOURCE_NAME` | `How to Invest Money` | Citation label |
| `chatbot.knowledge.fetch-timeout` | `CHATBOT_KNOWLEDGE_FETCH_TIMEOUT` | `10s` | Connect and read timeout (1–60 seconds) |
| `chatbot.knowledge.chunk-size-chars` | `CHATBOT_KNOWLEDGE_CHUNK_SIZE_CHARS` | `800` | Maximum chunk size |
| `chatbot.knowledge.chunk-overlap-chars` | `CHATBOT_KNOWLEDGE_CHUNK_OVERLAP_CHARS` | `150` | Overlap between chunks |
| `chatbot.knowledge.top-k` | `CHATBOT_KNOWLEDGE_TOP_K` | `4` | Maximum retrieved passages |
| `chatbot.knowledge.max-context-chars` | `CHATBOT_KNOWLEDGE_MAX_CONTEXT_CHARS` | `4000` | Maximum total retrieved passage text |

Provider failures produce generic error responses (`502`/`503`/`504`); details are logged server-side only. The app retries timeouts, network errors and `429`/`5xx` at most twice with exponential backoff, and never retries `400`/`401`/`403`.

The default `gpt-5.4` model costs more per token than the previous `gpt-4o-mini` model. Check [OpenAI pricing](https://developers.openai.com/api/docs/pricing) and your account's model access before enabling it. Set `CHATBOT_OPENAI_MODEL` to an accessible, Chat Completions-compatible model if needed.

Logging is metadata-only (request id, question length, latency); question and answer content is never logged. Production runs at `INFO` level.

Questions are sent to OpenAI, a third-party provider; do not submit personal data. See OpenAI's data retention policy for how submitted data is handled.

## Testing

Run the project's verification suite:
```bash
./gradlew check
```

All tests run without provider credentials or network calls; the OpenAI provider is tested against a mock HTTP server.
The source compatibility test is opt-in and accesses Project Gutenberg:

```bash
KNOWLEDGE_LIVE_TEST=true ./gradlew test --tests '*KnowledgeTest.live*' --rerun-tasks
```

The browser availability logic also has a standalone test using Node.js (no packages required):

```bash
node src/test/js/knowledge-status.test.js
```

## Provider implementations

`ChatbotService` retrieves passages and talks to a `ChatProvider` interface. `MockChatProvider` (profiles `default`/`local`/`test`) returns deterministic excerpts for local development and tests. `OpenAiChatProvider` (profile `prod`) calls the OpenAI Chat Completions API and is wired only when `OPENAI_API_KEY` is set.