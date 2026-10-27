# Security Enhancement

## Goal

Harden the Azure deployment defined in [azure-cloud-deployment.md](azure-cloud-deployment.md) towards zero trust principles (verify explicitly, least privilege, assume breach) while keeping the cost of a pay-as-you-go subscription low.

## Context

- Baseline already defined in the other specs (not repeated here):
  - [azure-cloud-deployment.md](azure-cloud-deployment.md): managed identity with `AcrPull`, ACR admin user disabled, HTTPS-only ingress, production settings (`SPRING_PROFILES_ACTIVE=prod`, `CHATBOT_PROVIDER=openai`, `INFO` logging), startup/liveness/readiness probes, replica cap, single resource group `chatbot-rg`.
  - [call-to-real-ai-chat-service.md](call-to-real-ai-chat-service.md): input length limit, output token cap, application rate limiting, generic errors, plain-text rendering, no content logging, key from environment variable only, fail-fast startup, health-only actuator.
- This feature owns only what those specs leave open:
  1. The public endpoint has no authentication; anyone can consume the AI quota.
  2. The AI key (and the Entra client secret) must not be stored in the Terraform state.
  3. No managed secret store or audit trail.
  4. Inbound access cannot be restricted (outbound restrictions stay out of scope, as in the base spec).
  5. The image tag `latest` is mutable, and the runtime image is larger than needed.
  6. The Terraform deployer identity has no defined least-privilege role.
  7. No image vulnerability scanning or security monitoring.
- Single environment, prefix `chatbot-`, Terraform in `infra/`, same as the base feature.
- Where this spec conflicts with the base specs, this spec wins once implemented; the required changes to the base specs are listed under Implementation Notes.

## Functional Requirements

- Require authentication for all requests to the application (UI and API) using Container Apps built-in authentication with Microsoft Entra ID.
  - API calls without a session get `401`; browser page requests redirect to login.
  - Container App probes run inside the platform and bypass ingress authentication; exclude a path from authentication only if an external monitor needs it.
- Store the AI API key in Azure Key Vault and have the Container App read it through its managed identity (Key Vault secret reference), exposed to the app as `OPENAI_API_KEY`. The secret value is set outside Terraform so it never enters the state.
- Store the Entra client secret the same way (Key Vault, set outside Terraform), or use a federated identity credential if the platform supports it; no client secret may appear in code or state.
- Grant the managed identity only the roles it needs: `AcrPull` on the ACR and `Key Vault Secrets User` on the vault.
- Pin the real application image to an immutable tag (git SHA or build id) or digest; `latest` is not allowed for the deployed application revision. The public placeholder image used for the first bootstrap apply is the only exception.
- Restrict ingress with an optional IP allowlist variable (empty means open to authenticated users).
- Use a minimal runtime image (JRE only, non-root) instead of the current JDK/devcontainer base image to reduce attack surface.
- Scan the container image for vulnerabilities after the build and before the deployed revision is updated.
- Define a least-privilege role for the Terraform deployer scoped to `chatbot-rg`.
- Send Key Vault diagnostic logs and Container App logs to the existing Log Analytics workspace, and alert when the daily cap is reached (otherwise audit data is silently dropped).
- Rate limiting, the replica cap, the provider spend limit and the health-only actuator are required by the base specs and verified here, not redefined.

## Configuration

New or changed Terraform variables (`infra/variables.tf`):

- `entra_client_id` (string): app registration used by built-in authentication.
- `entra_tenant_id` (string, default current tenant).
- `allowed_ip_ranges` (list(string), default `[]`).
- `image_tag` (string, no default for the real image; validation rejects `latest`), or `image_digest`.
- Key Vault name derived from the prefix and the existing random suffix (3-24 chars, alphanumeric and hyphens, globally unique).

Removed from the base spec: the `ai_api_key` variable and `TF_VAR_ai_api_key`; the `latest` default of `image_tag`.

Application configuration:

- `OPENAI_API_KEY`: provided through the Key Vault reference, not a Terraform value.
- Rate limiting is keyed on the authenticated user id supplied by the platform authentication headers (only trustworthy because the app is reachable solely through the ingress), falling back to client IP. Rate limit values come from the AI chat service spec.

Secrets created outside Terraform:

```
az keyvault secret set --vault-name <vault> --name ai-api-key --value <key>
az keyvault secret set --vault-name <vault> --name entra-client-secret --value <secret>
```

The user running these needs `Key Vault Secrets Officer` on the vault, granted temporarily and removed afterwards.

## Implementation Notes

- Add to `infra/main.tf`:
  - `azurerm_key_vault` (Standard SKU, RBAC authorization enabled, soft delete on, purge protection optional).
  - `azurerm_role_assignment` for the managed identity (`Key Vault Secrets User`).
  - `azurerm_monitor_diagnostic_setting` for the vault to Log Analytics, plus an alert on the Log Analytics daily cap.
  - Container App `secret` blocks using `key_vault_secret_id` and `identity` (user-assigned identity).
  - Built-in authentication: `azurerm_container_app` has no native authentication resource, so use `azapi_resource` (`Microsoft.App/containerApps/authConfigs`); verify against the current provider versions and pin them.
  - Ingress `ip_security_restriction` blocks generated from `allowed_ip_ranges`.
- Entra app registration: the redirect URI is `https://<app fqdn>/.auth/login/aad/callback`, which is only known after the Container App exists. Document the order: apply, register or update the redirect URI with the output FQDN, then enable authentication (second apply).
- Ordering with the base deployment flow (the app fails fast without `OPENAI_API_KEY`, and a Key Vault reference to a missing secret fails the revision):
  1. `terraform apply` with the placeholder image: creates the vault, identity and roles; no AI settings or Key Vault references are attached yet.
  2. Set the secrets in Key Vault.
  3. `az acr build --registry <acr> --image chatbot:<git-sha> .`, then scan the image.
  4. `terraform apply -var image_tag=<git-sha> -var use_placeholder_image=false`: attaches the Key Vault references, application settings and authentication.
- Key Vault soft delete: `terraform destroy` leaves the vault soft-deleted and its name reserved. Set `purge_soft_delete_on_destroy = true` in the provider `features` block (acceptable only because this is a single, non-critical environment) so a full teardown leaves nothing behind.
- Dockerfile: replace the runtime stage base image with a minimal JRE image, run as a non-root user, keep `EXPOSE 8080` and bounded JVM memory.
- Image scanning: `trivy image` against the image in ACR (or Microsoft Defender for Containers if budget permits); stop the deploy on `HIGH`/`CRITICAL` findings that have fixes.
- Terraform security scanning: run `trivy config infra/` or `checkov -d infra/` and fix findings or document accepted exceptions.
- Deployer role: custom role or `Contributor` scoped to `chatbot-rg` plus `User Access Administrator` limited to that scope (needed for role assignments); never subscription-wide Owner.
- State handling: state is still local and still sensitive (resource ids, settings); keep it out of git. The AI key and Entra client secret are not in it. An encrypted remote backend remains a follow-up.
- Cost: Key Vault Standard is billed per operation (cents per month at this scale); Entra built-in authentication has no extra Azure charge; Defender plans are optional and off by default.
- Required updates to [azure-cloud-deployment.md](azure-cloud-deployment.md) when this is implemented: remove `ai_api_key` and `TF_VAR_ai_api_key`, change the `image_tag` default, change the secret handling and acceptance criteria 4 and 9 to the Key Vault reference, and add the extra steps to the deployment flow.

## Error Handling

- Unauthenticated request: `401` for API calls or redirect to Entra login for pages; no application data returned.
- Authenticated but rate limited: `429` with `Retry-After` and the stable generic error body defined in the AI chat service spec.
- Key Vault secret missing or inaccessible: the revision fails to become ready; document checking the role assignment and `az keyvault secret show`.
- Role assignment not yet propagated: re-run the apply; use `depends_on` between the role assignment and the Container App.
- Redirect URI mismatch at login: update the app registration with the output FQDN.
- Key Vault name already soft-deleted: purge it (`az keyvault purge`) or rely on `purge_soft_delete_on_destroy`.
- Image tag `latest` supplied for the real image: Terraform validation fails with a clear message.
- Vulnerability scan finds HIGH/CRITICAL issues: the deploy step stops and reports the findings.
- Log Analytics daily cap reached: an alert fires; raise the cap or investigate unusual traffic.

## Testing Requirements

- `terraform fmt -check` and `terraform validate` pass; `trivy config` or `checkov` reports no unaccepted HIGH findings.
- `terraform plan` shows no secret values; the state file contains neither the AI key nor the Entra client secret (search for known test values).
- Unauthenticated `curl` to the UI and API is rejected; an authenticated browser session succeeds, including after a cold start.
- Platform probes keep passing with authentication enabled.
- Requests from an IP outside `allowed_ip_ranges` are denied when the list is set.
- The Container App reads the Key Vault secrets; a user without the vault role cannot.
- The deployed revision uses an immutable tag; supplying `latest` fails validation.
- Verify, without redefining, the base-spec controls in the deployed app: rate limit returns `429`, only the health actuator endpoint responds, and logs contain no question or answer content.
- The rate limit key uses the authenticated user, and a spoofed identity header sent from outside does not change it.
- The runtime image runs as non-root on the minimal JRE base and passes the scan.
- After `terraform destroy`, the Key Vault is purged and nothing remains in `chatbot-rg`.

## Documentation Requirements

- Update [README.md](../../README.md) with:
  - How to register the Entra app, set `entra_client_id`, and update the redirect URI.
  - How to store the AI key and Entra client secret in Key Vault and the temporary role needed.
  - The new deployment order and how to build and deploy with an immutable tag.
  - How to configure `allowed_ip_ranges`.
- Document the zero trust mapping: verify explicitly (Entra authentication), least privilege (scoped roles and the deployer role), assume breach (audit logs, scanning, rate limit, minimal image).
- Document the required deployer role and the sensitivity of local Terraform state.
- Document residual risks (no private networking, no outbound restriction, local state, public ACR).
- Document the additional monthly cost and how to disable optional components.

## Acceptance Criteria

1. Only authenticated users can reach the chat UI and API, and platform health probes still pass.
2. The AI API key and Entra client secret are stored in Key Vault, referenced through the managed identity, and absent from code, logs, outputs and Terraform state.
3. The managed identity holds only `AcrPull` and `Key Vault Secrets User`.
4. The deployed real image is pinned to an immutable tag or digest, and runs non-root on a minimal JRE base.
5. Base-spec controls (rate limiting, replica cap, provider spend limit, health-only actuator, no content logging) are verified in the deployed app.
6. Image and Terraform scans run with no unaccepted HIGH/CRITICAL findings.
7. Key Vault and Container App logs are available in Log Analytics, with an alert on the daily cap.
8. The deployment order (placeholder apply, secrets, build and scan, real apply) works from an empty subscription.
9. `terraform destroy` removes everything, including the Key Vault (purged).
10. README documents setup, deployment, residual risks, and extra cost, and [azure-cloud-deployment.md](azure-cloud-deployment.md) is updated to match.

## Out of Scope

- Private networking: VNet integration, Private Endpoints, Azure Firewall, outbound restrictions, and Premium ACR.
- Web Application Firewall, Front Door, or Application Gateway.
- Remote encrypted Terraform state backend (documented as a follow-up).
- Customer-managed encryption keys and HSM-backed Key Vault.
- Automated secret rotation.
- Full user management, roles, and application-level authorization beyond Entra login.
- CI/CD pipelines.
- Compliance certifications and penetration testing.
