# Chatbot Implementation Instructions

## Goal

Implement a clean, maintainable Kotlin Spring Boot chatbot flow:

- Accept a question entered in `src/main/resources/static/index.html`.
- Send the question to a Kotlin backend service.
- Have the backend service call a provider abstraction representing OpenAI/ChatGPT.
- Use a mock provider implementation for now. Do not make a real external API call.
- Return the provider response to the browser.
- Display the response in `index.html`.

## Implementation Requirements

- Follow the existing package name and project structure.
- Use clear Kotlin names, immutable values where practical, constructor injection, and small focused classes.
- Keep HTTP concerns in a controller and chatbot/provider logic in services.
- Define request and response DTOs when they make the API contract clearer.
- Prefer a provider interface, such as `ChatProvider`, with a deterministic mock implementation.
- Keep the mock response dependent on the submitted question so the end-to-end behavior is observable.
- Return appropriate HTTP status codes and useful validation errors for blank or missing questions.
- Use Spring MVC conventions already present in the project.
- Do not add API keys, secrets, real OpenAI calls, or unnecessary dependencies.
- Preserve existing endpoints and behavior unless the implementation requires a focused change.

## Frontend Requirements

- Update `index.html` to submit the user's question to the backend using `fetch`.
- Use the backend endpoint and JSON contract consistently.
- Show a loading state while waiting for the response.
- Render the returned answer in the page.
- Display a user-friendly error when the request fails.
- Prevent submission of blank questions.
- Keep the existing page styling and structure unless a small change is required for the workflow.

## Testing Requirements

- Add unit tests for the mock provider/service behavior.
- Add controller or web-layer tests for a valid question and invalid blank input.
- Verify the response JSON and HTTP status codes.
- Do not test only mock interactions; assert real returned values and observable behavior.
- Run the project's Gradle verification task, normally `./gradlew check`, after implementation.

## Documentation Requirements

Update `README.md` with:

- What the application does.
- The backend endpoint and request/response example.
- How to run the application locally.
- How to run the tests.
- The fact that the current provider is mocked and does not call OpenAI/ChatGPT.
- The future integration point for replacing the mock provider with a real client.

## Acceptance Criteria

The implementation is complete when:

1. A user enters a non-blank question in the chat UI.
2. The browser sends the question to the Kotlin backend.
3. The backend returns a deterministic mocked chatbot answer.
4. The answer is rendered in `index.html`.
5. Blank input is rejected with a clear user-facing message.
6. Automated tests cover the service and HTTP behavior.
7. `./gradlew check` succeeds.
8. The README explains setup, usage, testing, and the mocked integration.

## Out of Scope

- Real OpenAI or ChatGPT API calls.
- API key configuration or secret management.
- Streaming responses.
- Authentication, persistence, conversation history, or production deployment changes.
- Unrelated refactoring or visual redesign.