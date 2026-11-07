data "azurerm_client_config" "current" {}

resource "random_string" "suffix" {
  length  = 5
  upper   = false
  special = false
}

locals {
  suffix      = "${var.name_prefix}-${var.environment}-weu"
  compact     = replace("${var.name_prefix}${var.environment}weu", "-", "")
  unique      = random_string.suffix.result
  is_prod     = var.environment == "prod"
  tags = {
    app         = var.name_prefix
    environment = var.environment
  }
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.suffix}"
  location = var.location
  tags     = local.tags
}

# ---------- Observability ----------

resource "azurerm_log_analytics_workspace" "main" {
  name                = "log-${local.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = var.log_retention_days
  tags                = local.tags
}

resource "azurerm_application_insights" "main" {
  name                = "appi-${local.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  workspace_id        = azurerm_log_analytics_workspace.main.id
  application_type    = "java"
  tags                = local.tags
}

# ---------- Identity ----------

resource "azurerm_user_assigned_identity" "app" {
  name                = "id-${local.suffix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# ---------- Container registry ----------

resource "azurerm_container_registry" "main" {
  name                = "acr${local.compact}${local.unique}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "Basic"
  admin_enabled       = false
  tags                = local.tags
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

# ---------- Key Vault ----------

resource "azurerm_key_vault" "main" {
  name                       = "kv-${local.compact}-${local.unique}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = local.is_prod
  soft_delete_retention_days = 7
  tags                       = local.tags
}

resource "azurerm_role_assignment" "kv_secrets_user" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

# The identity running Terraform must be able to write the secret.
resource "azurerm_role_assignment" "kv_secrets_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_secret" "openai_api_key" {
  name         = "openai-api-key"
  value        = var.openai_api_key
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.kv_secrets_officer]
}

# ---------- Storage (knowledge source files) ----------

resource "azurerm_storage_account" "knowledge" {
  name                            = "st${local.compact}${local.unique}"
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  account_tier                    = "Standard"
  account_replication_type        = local.is_prod ? "ZRS" : "LRS"
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  tags                            = local.tags
}

resource "azurerm_storage_container" "knowledge" {
  name                  = "knowledge"
  storage_account_id    = azurerm_storage_account.knowledge.id
  container_access_type = "private"
}

resource "azurerm_role_assignment" "blob_reader" {
  scope                = azurerm_storage_account.knowledge.id
  role_definition_name = "Storage Blob Data Reader"
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

# ---------- Container Apps ----------

resource "azurerm_container_app_environment" "main" {
  name                       = "cae-${local.suffix}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  tags                       = local.tags
}

resource "azurerm_container_app" "chatbot" {
  name                         = "ca-${local.suffix}"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"
  tags                         = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.app.id]
  }

  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.app.id
  }

  secret {
    name                = "openai-api-key"
    key_vault_secret_id = azurerm_key_vault_secret.openai_api_key.versionless_id
    identity            = azurerm_user_assigned_identity.app.id
  }

  ingress {
    external_enabled           = true
    target_port                = var.container_port
    transport                  = "auto"
    allow_insecure_connections = false

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    http_scale_rule {
      name                = "http-concurrency"
      concurrent_requests = "50"
    }

    container {
      name   = "chatbot"
      image  = var.container_image
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "azure"
      }
      env {
        name        = "OPENAI_API_KEY"
        secret_name = "openai-api-key"
      }
      env {
        name  = "APPLICATIONINSIGHTS_CONNECTION_STRING"
        value = azurerm_application_insights.main.connection_string
      }

      liveness_probe {
        transport = "HTTP"
        path      = "/actuator/health/liveness"
        port      = var.container_port
      }

      readiness_probe {
        transport = "HTTP"
        path      = "/actuator/health/readiness"
        port      = var.container_port
      }

      startup_probe {
        transport               = "HTTP"
        path                    = "/actuator/health/liveness"
        port                    = var.container_port
        failure_count_threshold = 30
        interval_seconds        = 5
      }
    }
  }

  depends_on = [
    azurerm_role_assignment.acr_pull,
    azurerm_role_assignment.kv_secrets_user,
  ]

  lifecycle {
    # The image is updated by the CI/CD pipeline.
    ignore_changes = [template[0].container[0].image]
  }
}

