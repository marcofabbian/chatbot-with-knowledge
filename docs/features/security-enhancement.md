# Security Enhancement

## Goal

Harden the Azure deployment defined in [azure-cloud-deployment.md](azure-cloud-deployment.md) towards zero trust principles (verify explicitly, least privilege, assume breach) while keeping the cost of a pay-as-you-go subscription low.

## Context

- Baseline already in place: managed identity with `AcrPull`, ACR admin user disabled, HTTPS-only ingress, AI key as a Container App secret, non-root container, single resource group `chatbot-rg`.
- Gaps addressed by this feature:
  1. Public endpoint has no authentication; anyone can consume the AI quota.
  2. The AI key is stored in plain text in the local Terraform state.
  3. No managed secret store, rotation, or audit trail.
  4. No network restriction on inbound or outbound traffic.
  5. The image tag `latest` is mutable.
  6. The Terraform deployer identity has no defined least-privilege role.
  7. No image vulnerability scanning or security monitoring.
- Single environment, prefix `chatbot-`, Terraform in `infra/`, same as the base feature.

## Functional Requirements

- Require authentication for all requests to the application using Container Apps built-in authentication (Microsoft Entra ID); unauthenticated requests get `401`/redirect to login. The health probe path is excluded.
- Store the AI API key in Azure Key Vault and have the Container App read it through its managed identity (Key Vault secret reference). The secret value is set outside Terraform so it never enters the state.
- Grant the managed identity only the roles it needs: `AcrPull` on the ACR and `Key Vault Secrets User` on the vault.
- Pin the deployed image to an immutable tag (build id or commit SHA) or digest; `latest` is not allowed for the deployed revision.
- Limit abuse and cost: rate limiting at application level, `max_replicas` cap, and a hard monthly spend limit configured on the AI provider key.
- Restrict ingress with an optional IP allowlist variable (empty means open to authenticated users).
- Restrict the actuator to the health endpoint only; no other management endpoints are exposed.
- Scan the container image for vulnerabilities before deployment.
- Define a least-privilege role for the Terraform deployer scoped to `chatbot-rg`.
- Enable security audit data: Key Vault diagnostic logs and Container App logs sent to the existing Log Analytics workspace.

## Configuration

New or changed Terraform variables (`infra/variables.tf`):

- `entra_client_id` (string): app registration used by built-in authentication.
- `entra_tenant_id` (string, default current tenant).
- `allowed_ip_ranges` (list(string), default `[]`).
- `image_digest` or `image_tag` (string, required for deployed revision; validation rejects `latest`).
- `key_vault_name` derived from prefix with random suffix (3-24 chars, alphanumeric and hyphens, globally unique).

Application configuration:

- `OPENAI_API_KEY`: provided through the Key Vault reference, not a Terraform value.
- `management.endpoints.web.exposure.include=health` in `application.properties`.
- Rate limit settings (requests per minute per client), for example `chatbot.rate-limit.requests-per-minute`.

Removed: `ai_api_key` Terraform variable (value is no longer passed through Terraform).

Secret creation (outside Terraform):

```
az keyvault secret set --vault-name <vault> --name ai-api-key --value <key>
```

The user running this needs `Key Vault Secrets Officer` on the vault, granted temporarily.

## Implementation Notes

- Add to `infra/main.tf`:
  - `azurerm_key_vault` (Standard SKU, RBAC authorization enabled, soft delete on, purge protection optional).
  - `azurerm_role_assignment` for the managed identity (`Key Vault Secrets User`).
  - `azurerm_monitor_diagnostic_setting` for the vault to Log Analytics.
  - `azurerm_container_app_environment` unchanged; Container App secret uses `key_vault_secret_id` and `identity`.
  - Authentication via `azapi` or `azurerm` `auth` block (Entra provider) on the Container App; document which provider is used.
  - Ingress `ip_security_restriction` blocks generated from `allowed_ip_ranges`.
- Key Vault soft delete: `terraform destroy` leaves the vault in a soft-deleted state and its name stays reserved. Set `purge_soft_delete_on_destroy = true` in the provider `features` block (only acceptable because there is a single non-production environment), so a full teardown leaves nothing behind.
- Deployment flow change: apply infra, set the secret in Key Vault, build image with `az acr build --image chatbot:<git-sha>`, apply with the immutable tag.
- Application rate limiting: use a lightweight in-memory filter (e.g. Bucket4j) keyed on authenticated user or client IP; no external store needed with a small replica count.
- Image scanning: run `trivy image` against the built image (or enable Microsoft Defender for Containers if budget permits); fail on `HIGH`/`CRITICAL` findings with fixes available.
- Terraform security scanning: run `trivy config infra/` or `checkov -d infra/` and fix findings or document accepted exceptions.
- Deployer role: custom role or `Contributor` scoped to `chatbot-rg` plus `User Access Administrator` limited to that scope (needed for role assignments); never subscription-wide Owner.
- State handling: state is still local; document that `terraform.tfstate` is sensitive and must stay out of git. The secret no longer appears in it. An encrypted remote backend remains a follow-up.
- Cost: Key Vault Standard is billed per operation (cents per month at this scale); Entra built-in authentication has no extra Azure charge; Defender plans are optional and not enabled by default.

## Error Handling

- Unauthenticated request: `401` or redirect to Entra login; no application data returned.
- Authenticated but rate limited: `429` with a `Retry-After` header and a stable JSON error body.
- Key Vault secret missing or inaccessible at startup: the Container App revision fails to become ready; document checking the role assignment and `az keyvault secret show`.
- Role assignment not yet propagated: retry the apply; use `depends_on` ordering between role assignment and Container App.
- Key Vault name already soft-deleted: purge it (`az keyvault purge`) or rely on `purge_soft_delete_on_destroy`.
- Image tag `latest` supplied: Terraform validation fails with a clear message.
- Vulnerability scan finds HIGH/CRITICAL issues: the deploy step stops and reports the findings.

## Testing Requirements

- `terraform fmt -check` and `terraform validate` pass; `trivy config` or `checkov` reports no unaccepted HIGH findings.
- `terraform plan` shows no secret values; the state file does not contain the AI key (search for a known test value).
- Unauthenticated `curl` to the app URL is rejected; authenticated browser session succeeds.
- Health probe path works without authentication; other actuator paths return `404`.
- Exceeding the rate limit returns `429`; normal usage is unaffected.
- Requests from an IP outside `allowed_ip_ranges` are denied when the list is set.
- Container App can read the Key Vault secret; a user without the vault role cannot.
- The deployed revision uses an immutable tag; setting `latest` fails validation.
- Unit tests for the rate limit filter and the actuator exposure configuration, without network calls.
- After `terraform destroy`, the Key Vault is purged and nothing remains in `chatbot-rg`.

## Documentation Requirements

- Update [README.md](../../README.md) with:
  - How to register the Entra app and set `entra_client_id`.
  - How to store the AI key in Key Vault and the temporary role needed.
  - How to build and deploy with an immutable tag.
  - How to configure `allowed_ip_ranges`.
- Document the zero trust mapping: verify explicitly (Entra auth), least privilege (scoped roles), assume breach (audit logs, scanning, rate limit).
- Document the required deployer role and the sensitivity of local Terraform state.
- Document residual risks (no private networking, local state, public ACR).
- Document the additional monthly cost and how to disable optional components.

## Acceptance Criteria

1. Only authenticated users can reach the chat UI and API; the health probe remains available.
2. The AI API key is stored in Key Vault, referenced through the managed identity, and absent from code, logs, outputs and Terraform state.
3. The managed identity holds only `AcrPull` and `Key Vault Secrets User`.
4. The deployed image is pinned to an immutable tag or digest.
5. Rate limiting and a replica cap protect the AI quota, and a spend limit is documented on the provider key.
6. Only the health actuator endpoint is exposed.
7. Image and Terraform scans run with no unaccepted HIGH/CRITICAL findings.
8. Key Vault and Container App logs are available in Log Analytics.
9. `terraform destroy` removes everything, including the Key Vault (purged).
10. README documents setup, deployment, residual risks, and extra cost.

## Out of Scope

- Private networking: VNet integration, Private Endpoints, Azure Firewall, and Premium ACR.
- Web Application Firewall, Front Door, or Application Gateway.
- Remote encrypted Terraform state backend (documented as a follow-up).
- Customer-managed encryption keys and HSM-backed Key Vault.
- Automated secret rotation.
- Full user management, roles, and application-level authorization beyond Entra login.
- Compliance certifications and penetration testing.
