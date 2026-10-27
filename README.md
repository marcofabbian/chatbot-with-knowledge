# Chatbot with Knowledge

This project is a Kotlin Spring Boot chatbot that answers questions about:
- securities trade lifecycle
- custody and clearing services
- clearing and settlements

The application can run with a mocked provider (default, for local development and tests) or with the real OpenAI chat provider (prod profile). The backend exposes a REST endpoint that accepts a question and returns the provider's answer.

## Running locally (mock provider)

1. Ensure Java 17 is installed and available on your PATH.
2. Start the application:
   `./gradlew bootRun`
3. Open http://localhost:8080 in a browser.

The mock provider is active in the `default`, `local` and `test` profiles and requires no credentials.

## Running with the real AI provider

1. Set the API key as an environment variable (never in `application.properties` or committed files):
   `export OPENAI_API_KEY=sk-...`
2. Start with the prod profile:
   `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun`

The application fails at startup if the prod profile is active without `OPENAI_API_KEY`. Set a hard monthly spend limit on the key in the OpenAI dashboard.

## Backend endpoint

POST `/api/chat`

Request body:
```json
{
  "question": "What is custody and clearing?"
}
```

Response:
```json
{
  "answer": "Mock answer: 'What is custody and clearing?' -> This chatbot can explain custody and clearing services in the securities trade lifecycle."
}
```

If the question is blank, the service returns HTTP 400 with a JSON error payload such as:
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
| `chatbot.openai.model` | `CHATBOT_OPENAI_MODEL` | `gpt-4o-mini` | Model used for answers |
| `chatbot.openai.max-output-tokens` | `CHATBOT_OPENAI_MAX_OUTPUT_TOKENS` | `500` | Caps the provider response size |
| `chatbot.openai.connect-timeout` | `CHATBOT_OPENAI_CONNECT_TIMEOUT` | `5s` | Connect timeout |
| `chatbot.openai.read-timeout` | `CHATBOT_OPENAI_READ_TIMEOUT` | `30s` | Read timeout |
| `chatbot.max-question-length` | `CHATBOT_MAX_QUESTION_LENGTH` | `1000` | Questions longer than this are rejected with 400 |
| `chatbot.rate-limit.requests-per-minute` | `CHATBOT_RATE_LIMIT_REQUESTS_PER_MINUTE` | `10` | Per-client limit on `POST /api/chat`; excess gets 429 with `Retry-After` |

Provider failures produce generic error responses (`502`/`503`/`504`); details are logged server-side only. The app retries timeouts, network errors and `429`/`5xx` at most twice with exponential backoff, and never retries `400`/`401`/`403`.

Logging is metadata-only (request id, question length, latency); question and answer content is never logged. Production runs at `INFO` level.

Questions are sent to OpenAI, a third-party provider; do not submit personal data. See OpenAI's data retention policy for how submitted data is handled.

## Testing

Run the project's verification suite:
```bash
./gradlew check
```

All tests run without provider credentials or network calls; the OpenAI provider is tested against a mock HTTP server.

## Provider implementations

`ChatbotService` talks to a `ChatProvider` interface. `MockChatProvider` (profiles `default`/`local`/`test`) returns deterministic answers for local development and tests. `OpenAiChatProvider` (profile `prod`) calls the OpenAI Chat Completions API and is wired only when `OPENAI_API_KEY` is set.