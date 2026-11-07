# Terraform: Azure infrastructure (West Europe)

Provisions: resource group, Log Analytics, Application Insights, ACR, Key Vault (RBAC) with the OpenAI key secret, user-assigned identity, Blob storage for knowledge files, Container Apps environment and app.

## Prerequisites
- Terraform >= 1.6, Azure CLI, `az login`
- A storage account for remote state (the `backend "azurerm" {}` block is configured at init)

## Usage

```bash
cd infra/terraform
export TF_VAR_subscription_id="<subscription-id>"
export TF_VAR_openai_api_key="<openai-key>"

terraform init \
  -backend-config="resource_group_name=<state-rg>" \
  -backend-config="storage_account_name=<state-storage>" \
  -backend-config="container_name=tfstate" \
  -backend-config="key=chatbot-dev.tfstate"

terraform plan  -var-file=environments/dev.tfvars
terraform apply -var-file=environments/dev.tfvars
```

## First deploy
The Container App starts with a placeholder image (port 80). After the first push to ACR, deploy the real image:

```bash
az acr build -r $(terraform output -raw acr_name) -t chatbot:v1 ../..
az containerapp update -n $(terraform output -raw container_app_name) \
  -g $(terraform output -raw resource_group_name) \
  --image $(terraform output -raw acr_login_server)/chatbot:v1 \
  --target-port 8080
```

The image is ignored by Terraform (`ignore_changes`), so CI/CD owns it. Set `container_port = 8080` in tfvars once the real image is in place so the probes and ingress match.

## Notes
- The OpenAI key is stored in Terraform state. Use a private, encrypted state backend.
- Prod uses ZRS storage and Key Vault purge protection. Private endpoints and a NAT Gateway are not included yet.

