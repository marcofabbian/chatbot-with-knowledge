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
- Display a useful error when the provider request fails.
- Do not expose provider credentials to the browser.

## Configuration

- Provider API key: [environment variable or configuration property]
- Provider base URL: [configuration property, if applicable]
- Model: [model name/configuration property]
- Request timeout: [value and unit]
- Retry policy: [number of retries and retryable failures]

## Implementation Notes

- [Describe the provider client or SDK to use.]
- [Describe the abstraction that separates provider code from chatbot logic.]
- [Describe request and response mapping.]
- [Describe logging and sensitive-data handling.]
- [Describe whether the mock provider remains available for local development and tests.]

## Error Handling

- Invalid or blank input: [expected status and response]
- Authentication or authorization failure: [expected behavior]
- Rate limit response: [expected behavior]
- Provider timeout or network failure: [expected behavior]
- Unexpected provider response: [expected behavior]

## Testing Requirements

- Test request and response mapping with representative provider payloads.
- Test successful chatbot responses through the service boundary.
- Test provider failures, timeouts, and malformed responses.
- Test that credentials are not included in logs or responses.
- Keep unit tests deterministic and avoid real provider calls in automated builds.

## Documentation Requirements

- Document required configuration and environment variables.
- Document how to run the application with the real provider.
- Document how to run tests without provider credentials.
- Document the selected model, limits, and expected costs where applicable.

## Acceptance Criteria

1. A user submits a non-blank question from the existing chat UI.
2. The backend sends the question to the configured real AI provider.
3. The provider answer is returned using the existing API contract.
4. Provider credentials remain server-side and are not logged.
5. Provider failures produce a clear, stable application response.
6. Automated tests cover successful and failing provider interactions without network calls.
7. The application can still be tested locally without real provider credentials.

## Out of Scope

- Conversation persistence or history.
- Authentication and authorization for application users.
- Streaming responses.
- Provider-specific features not required by the existing chat flow.
