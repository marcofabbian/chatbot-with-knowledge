# GitHub CI/CD Implementation

## Goal

Implement a GitHub Actions CI/CD pipeline for the Chatbot project. The pipeline
must clean, build, and test the application; create and scan a Docker image;
and publish the image to Docker Hub only after all checks pass.

## Workflow Definition

Create the workflow file at `.github/workflows/ci-cd.yml`.

The workflow must run for pull requests targeting `main` and pushes to `main`.
It must use an Ubuntu GitHub-hosted runner and JDK 17, which matches the
project's Gradle toolchain.

## Pipeline Requirements

Run the following steps in order:

1. Check out the repository source.
2. Set up JDK 17 and make `gradlew` executable.
3. Clean previous build output with `./gradlew clean --no-daemon`.
4. Build the project and run its unit tests with
	`./gradlew build --no-daemon`.
5. Build the Docker image using the repository `Dockerfile`.
6. Run Grype against the locally built image. Configure the scan to fail for
	`high` and `critical` vulnerabilities.
7. On direct pushes to `main` only, authenticate to Docker Hub and push the
	image.

All Gradle tasks must use the repository wrapper, `./gradlew`; do not invoke a
system-installed Gradle executable.

## Docker Image Publishing

Tag the image as:

- `${DOCKERHUB_USERNAME}/chatbot-with-knowledge:${GITHUB_SHA}`
- `${DOCKERHUB_USERNAME}/chatbot-with-knowledge:latest`

The immutable commit-SHA tag and the `latest` tag must both be pushed to Docker
Hub after the Grype scan succeeds. Pull-request workflows must build and scan
the image but must not log in to or publish to Docker Hub.

Configure these GitHub repository secrets:

| Secret | Purpose |
| --- | --- |
| `DOCKERHUB_USERNAME` | Docker Hub account or organization that owns the image repository. |
| `DOCKERHUB_TOKEN` | Docker Hub access token with permission to push the image. |

## Failure Behavior

Every pipeline step is mandatory. Do not configure `continue-on-error` for any
step. GitHub Actions must mark the job as failed when checkout, Gradle clean,
build, unit tests, image build, Grype scan, Docker Hub login, or image push
fails. A failure must prevent all later steps, particularly image publication.

## Acceptance Criteria

The implementation is complete when it runs successfully in **GitHub Actions**:

1. A pull request to `main` cleans, builds, tests, creates, and Grype-scans the
	Docker image without publishing it.
2. A push to `main` completes those checks and then publishes both tags to
	Docker Hub.
3. A failing required step fails the entire workflow and prevents image
	publication.
4. The GitHub repository contains `.github/workflows/ci-cd.yml` implementing
	these requirements.

The original request refers to GitLab in its acceptance criterion. This feature
targets a GitHub repository, so the implementation must be validated in GitHub
Actions rather than GitLab CI/CD.
