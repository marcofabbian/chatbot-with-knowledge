 # Call to Real AI Chat Service

## Goal

Replace the mocked chatbot provider with a real AI chat service while preserving the existing browser and backend contract.

## Context

- Current provider: Currently the provider is a mock to display some information.
- Target provider: The target provider is open ai/chatgtp. Initially the provider will not have any knowledge file provided externally.
- Existing chat endpoint: [HTTP method and path]
- Existing request/response contract: [link or short description]

## Functional Requirements

- Accept a non-blank question from the existing chat UI.
- Send the question to the configured AI provider.
- Return the provider answer using the existing backend response contract.
- Reject questions longer than the configured maximum length before calling the provider.
- Limit the provider response size with a maximum output tokens setting.
- Display a generic, stable error when the provider request fails; never forward raw provider messages.
- Render the AI answer in the browser as plain text (no `innerHTML`) or sanitize it.
- Apply per-client rate limiting to the chat endpoint.
- Send a fixed system prompt with every request; treat model output as untrusted.
- Do not expose provider credentials to the browser.

## Configuration

- Provider API key: environment variable only (e.g. `OPENAI_API_KEY`); never in `application.properties` or committed files. The application fails at startup if it is missing when the real provider is active.
- Provider base URL: configuration property; must use `https`, and TLS verification must never be disabled.
- Model: [model name/configuration property]
- Request timeout: [value and unit] for both connect and read.
- Retry policy: maximum 2 retries with exponential backoff, only for timeouts, network errors and `429`/`5xx`; no retry on `400`/`401`/`403`.
- Maximum question length: [e.g. 1000 characters], configurable.
- Maximum output tokens: [value], configurable.
- Rate limit: [requests per minute per client], configurable.
- Provider selection: profile or property (`chatbot.provider=openai|mock`); the mock is only allowed in `local`/`test` profiles.

## Implementation Notes

- [Describe the provider client or SDK to use.]
- [Describe the abstraction that separates provider code from chatbot logic.]
- [Describe request and response mapping.]
- Logging: log metadata only (request id, question length, latency, status); never log question text, answers, request bodies or credentials. Use `INFO` for the application logger in production (the current `DEBUG` level in `application.properties` must not be used with the real provider).
- Define a fixed system prompt in configuration; do not include secrets or internal details in it. Keep user input in the user role only.
- The mock provider remains available for local development and tests, selected only by the `local`/`test` profile; production startup fails if the mock is active.
- Restrict CORS to allowed origins and accept only `application/json` on the chat endpoint.
- The UI must insert the answer with `textContent` (or a sanitizer), not `innerHTML`.
- Questions are sent to a third-party provider; do not send personal data deliberately and document it.

## Error Handling

- Invalid or blank input: `400` with a generic message.
- Question too long: `413` or `400` with a generic message stating the maximum length.
- Application rate limit exceeded: `429` with a `Retry-After` header.
- Provider authentication or authorization failure: `502`/`503` with a generic message; log the cause server-side without secrets and do not reveal it to the user.
- Provider rate limit response: `503` with a generic retry-later message after bounded retries.
- Provider timeout or network failure: `504`/`503` with a generic message.
- Unexpected or malformed provider response: `502` with a generic message.
- Error bodies never include provider messages, stack traces, request bodies or credentials.

## Testing Requirements

- Test request and response mapping with representative provider payloads.
- Test successful chatbot responses through the service boundary.
- Test provider failures, timeouts, and malformed responses.
- Test that credentials and question/answer content are not included in logs or responses.
- Test the maximum question length, rate limiting, and retry rules (no retry on `401`, bounded retries on `429`/`5xx`).
- Test that provider error details are not exposed in error responses.
- Test that the application fails at startup when the real provider is active without an API key, or when the mock is active in production.
- Test that the UI renders answers containing HTML/script as plain text.
- Keep unit tests deterministic and avoid real provider calls in automated builds.

## Documentation Requirements

- Document required configuration and environment variables.
- Document how to run the application with the real provider.
- Document how to run tests without provider credentials.
- Document the selected model, limits, and expected costs where applicable.
- Document the security settings: maximum question length, output token limit, rate limit, logging policy, and key handling.
- Document that questions are sent to a third-party provider and its data retention policy.
- Document that a hard spend limit must be set on the provider key.

## Acceptance Criteria

1. A user submits a non-blank question from the existing chat UI.
2. The backend sends the question to the configured real AI provider.
3. The provider answer is returned using the existing API contract.
4. Provider credentials remain server-side and are not logged.
5. Provider failures produce a clear, stable application response.
6. Automated tests cover successful and failing provider interactions without network calls.
7. The application can still be tested locally without real provider credentials.
8. Over-long questions are rejected, output tokens are capped, and the endpoint is rate limited.
9. Error responses are generic and never contain provider details.
10. Question and answer content is not logged, and production does not run at `DEBUG` level.
11. AI answers are rendered as plain text in the UI.
12. The API key comes only from an environment variable, and the mock provider cannot run in production.
13. Retries and timeouts are bounded as configured.

## Out of Scope

- Conversation persistence or history.
- Authentication and authorization for application users (see [security-enhancement.md](security-enhancement.md)).
- Streaming responses.
- Content moderation and advanced prompt-injection defenses beyond a fixed system prompt.
- Provider-specific features not required by the existing chat flow.
