# Azure Cloud Deployment

## Goal

Provision the Azure infrastructure for the chatbot with Terraform (create and fully destroy on demand) and deploy the containerized Spring Boot application to it, minimizing cost on a pay-as-you-go subscription.

## Context

- Application: Kotlin Spring Boot app, port `8080`, packaged by the existing multi-stage [Dockerfile](../../Dockerfile).
- Subscription: Azure pay-as-you-go. No free credits or committed discounts are assumed; idle cost must be near zero.
- IaC tool: Terraform with the `azurerm` provider, kept in a separate root directory `infra/`.
- Single environment: only one deployment exists (no dev/staging/prod split). There is no `environment` variable, suffix, or workspace; this keeps the Terraform code and naming minimal.
- Naming: every Azure resource is identified by the prefix `chatbot-` (Azure naming rules apply, see Implementation Notes).
- Target layout:

```
infra/
├── main.tf
├── variables.tf
├── outputs.tf
└── providers.tf
```

- The real AI provider API key (see [call-to-real-ai-chat-service.md](call-to-real-ai-chat-service.md)) must be injected as a secret at runtime.
- The application requires `OPENAI_API_KEY` as an environment variable, fails at startup without it, and refuses to run the mock provider in production; the deployment must provide production settings accordingly.
- Hardening beyond this baseline (authentication, Key Vault, immutable tags) is defined in [security-enhancement.md](security-enhancement.md), which supersedes the secret handling described here when implemented.

## Functional Requirements

- Create all infrastructure with `terraform apply` from `infra/`.
- Remove all infrastructure with `terraform destroy`; no orphaned billable resources may remain.
- Place every resource in a single resource group named `chatbot-rg` so deleting the group removes everything.
- Provision at minimum:
  - Resource group.
  - Azure Container Registry (ACR), `Basic` SKU.
  - Log Analytics workspace (30-day retention, daily cap set).
  - Azure Container Apps environment and one Container App running the chatbot image, with external HTTPS ingress on port `8080`.
- Scale the Container App to zero replicas when idle (`min_replicas = 0`, `max_replicas` small, e.g. 2) using the Consumption plan.
- Output the application public URL, ACR login server, and resource group name.
- Provide a documented deploy flow: build image, push to ACR, update the Container App revision.
- Tag every resource with `project=chatbot` and `managed_by=terraform`.
- Support a two-phase lifecycle: infra can be created once and the app redeployed many times without recreating infra; destroy is a single command.

## Configuration

- `providers.tf`: `terraform` block with required Terraform version and pinned `azurerm` provider version; `provider "azurerm"` with `features {}` and `subscription_id` from variable or `ARM_SUBSCRIPTION_ID`.
- `variables.tf` (all with descriptions):
  - `subscription_id` (string, required).
  - `prefix` (string, default `chatbot`).
  - `location` (string, default a low-cost region such as `westeurope`).
  - `image_tag` (string, default `latest`).
  - `use_placeholder_image` (bool, default `true`).
  - `container_cpu` (default `0.5`), `container_memory` (default `1Gi`); a JVM with Spring Boot is unlikely to run reliably with less.
  - `min_replicas` (default `0`), `max_replicas` (default `2`).
  - `ai_api_key` (string, `sensitive = true`, no default).
  - `app_settings` (map(string), non-secret application settings, see Implementation Notes).
  - `log_retention_days` (default `30`), `log_daily_quota_gb` (default `0.5`).
- Authentication: `az login` locally (or service principal via `ARM_*` environment variables); no credentials in code or committed `.tfvars`.
- Secrets passed via `TF_VAR_ai_api_key`; never committed.
- Optional `infra/terraform.tfvars.example` with non-secret sample values.
- State: local state for the first iteration; `*.tfstate*`, `.terraform/` and `*.tfvars` added to `.gitignore`.

## Implementation Notes

- Resource names (single environment, so no environment segment):

  | Resource | Name |
  |---|---|
  | Resource group | `chatbot-rg` |
  | Log Analytics workspace | `chatbot-log` |
  | Container Apps environment | `chatbot-cae` |
  | Container App | `chatbot-app` |
  | Managed identity | `chatbot-id` |
  | Container Registry | `chatbotacr<5 random chars>` |

  - Build names from `var.prefix` (e.g. `"${var.prefix}-rg"`) via a `locals` block.
  - ACR allows alphanumerics only and must be globally unique: `${var.prefix}acr${random_string.suffix.result}` (use the `random` provider, 5 lowercase chars, no special or uppercase).
- Prefer `azurerm_container_app_environment` and `azurerm_container_app` over App Service/AKS to avoid fixed monthly cost.
- Use a user-assigned managed identity with `AcrPull` role on the ACR; disable the ACR admin user.
- Store the AI key as a Container App `secret` and expose it to the app via `secret_name` environment variable reference as `OPENAI_API_KEY` (environment variable only, never in `application.properties`).
- Pass non-secret application settings as Container App environment variables built from `app_settings`. Defaults must include: `SPRING_PROFILES_ACTIVE=prod`, `CHATBOT_PROVIDER=openai`, `LOGGING_LEVEL_COM_MARCOFABBIAN_CHATBOT=INFO` (no `DEBUG`, so question and answer content is not logged), plus model, max question length, max output tokens, rate limit, timeouts and allowed CORS origins as defined in the AI chat service spec.
- Rate limiting in the application is in-memory, so with up to `max_replicas` replicas the effective limit is per replica; the replica cap bounds the total. Document this.
- Outbound HTTPS (443) to the AI provider must be allowed; the default Container Apps egress does this. Restrictions on egress are out of scope.
- Bootstrap issue: the Container App needs an image before the first push. Chosen approach: first `apply` uses a public placeholder image (`mcr.microsoft.com/k8se/quickstart:latest`) controlled by an `use_placeholder_image` bool variable; the ingress `target_port` must switch with it (`80` for the placeholder, `8080` for the real image) and the AI key and application settings are only injected with the real image; after pushing the real image, re-apply with `use_placeholder_image=false` and `image_tag`. Use `lifecycle { ignore_changes = [template[0].container[0].image] }` only if deployments are done outside Terraform.
- Deployment flow:
  1. `terraform apply` (infra + placeholder app).
  2. `az acr build --registry <acr> --image chatbot:<tag> .` (builds remotely, no local Docker needed).
  3. `terraform apply -var image_tag=<tag> -var use_placeholder_image=false` (or `az containerapp update --image ...`).
  4. Open the `app_url` output.
- Dockerfile note: the runtime image should expose `8080` and the Container App `target_port` must be `8080`; JVM memory should be bounded (e.g. `-XX:MaxRAMPercentage=75`).
- Add a Container App startup, liveness and readiness probe on the Spring Boot health endpoint (add `spring-boot-starter-actuator` if not already present, exposing only `health`). Use a generous startup probe delay because JVM cold starts after scale-to-zero are slow and the app fails fast if `OPENAI_API_KEY` is missing.
- Optional helper scripts in `scripts/` (`deploy.sh`) wrapping `az acr build` and `az containerapp update`; infra remains Terraform-only.
- Cost guardrails: Basic ACR (~5 USD/month), Container Apps consumption scaled to zero, log quota capped; document an optional `azurerm_consumption_budget_resource_group` with email alert.
- Indicative monthly cost (to verify with the Azure pricing calculator): ACR Basic ~5 USD, Container Apps ~0 USD when idle (free monthly grant of vCPU-seconds/requests applies), Log Analytics ~0-2 USD with the daily cap. Total idle estimate: ~5-8 USD/month; `terraform destroy` brings it to 0. AI provider usage is billed separately and is likely the dominant cost; set a hard monthly spend limit on the provider key.
- Use `terraform output` for non-secret values only; mark any secret output `sensitive = true`.

## Error Handling

- Missing or invalid `subscription_id`: Terraform fails at plan with a clear message; validate non-empty via a `validation` block.
- Not logged in to Azure: document `az login` / `az account set --subscription <id>` as prerequisite.
- ACR name collision: the random suffix prevents it; on failure, re-run apply.
- Quota or region unavailability: document changing `location`.
- Image pull failure: verify the managed identity `AcrPull` role assignment propagated (apply dependency ordering with `depends_on`).
- Partial apply or destroy failure: re-run the command; if blocked, delete the resource group with `az group delete --name <rg>` and remove local state.
- Application failing to start: inspect logs with `az containerapp logs show` or the Log Analytics workspace.
- Missing `OPENAI_API_KEY` or mock provider selected in production: the app exits at startup by design; check the secret and `CHATBOT_PROVIDER`/`SPRING_PROFILES_ACTIVE`.
- Cold start timeouts: the first request after scale-to-zero may be slow or fail; document it and tune the startup probe.

## Testing Requirements

- `terraform fmt -check` and `terraform validate` pass.
- `terraform plan` shows only expected resources and all names start with the `chatbot` prefix.
- After `apply`: the output URL returns HTTP 200 for the chat UI and the chat endpoint responds.
- Verify the deployed app uses the real provider (not the mock), runs at `INFO` log level, and does not log question or answer content.
- Verify the app fails to start when `OPENAI_API_KEY` is removed.
- Verify scale-to-zero: with no traffic, replicas drop to 0 and the app recovers on the next request (cold start accepted).
- After `destroy`: `az resource list --resource-group <rg>` returns not found and the resource group no longer exists.
- Verify no secrets appear in plan output, logs or committed files (`sensitive = true`).
- Optional: `tflint` and `checkov`/`trivy config` scans on `infra/`.

## Documentation Requirements

- Update [README.md](../../README.md) with a Deployment section:
  - Prerequisites: Azure CLI, Terraform, Docker (or `az acr build`), subscription access.
  - Commands: `terraform init`, `plan`, `apply`, `destroy` from `infra/`.
  - How to build, push and deploy a new image version.
  - How to pass `ai_api_key` securely.
  - The production environment settings and that questions are sent to a third-party AI provider.
- Document the resource list, naming convention and estimated monthly cost for pay-as-you-go.
- Document the full teardown procedure and how to confirm nothing billable remains.
- Document how to view logs and the app URL.

## Acceptance Criteria

1. `infra/` contains `main.tf`, `variables.tf`, `outputs.tf` and `providers.tf`.
2. `terraform apply` creates all resources, each named with the `chatbot` prefix and tagged.
3. The chatbot is reachable over HTTPS at the output URL and answers requests.
4. The AI API key is stored as a secret and never appears in code, state outputs, or logs.
5. The app scales to zero when idle, and the monthly idle cost is limited to ACR and minimal logging.
6. `terraform destroy` removes every resource, leaving nothing billable.
7. `terraform fmt` and `terraform validate` succeed.
8. README documents create, deploy, and destroy steps plus the cost estimate.
9. The deployed app runs with the real provider, production profile and `INFO` logging, and `OPENAI_API_KEY` comes only from a Container App secret.
10. The placeholder image bootstrap works on port `80` and switches to `8080` for the real image.

## Out of Scope

- Custom domain, DNS, and managed certificates.
- Multiple environments (dev/staging/prod), Terraform workspaces, and environment-specific variables.
- Remote Terraform state backend (Azure Storage) and CI/CD pipelines.
- Private networking, VNet integration, and Private Endpoints.
- Authentication for application users.
- High availability, multi-region, and autoscaling beyond simple replica limits.
- Knowledge-base storage or vector databases (e.g. Azure AI Search).
