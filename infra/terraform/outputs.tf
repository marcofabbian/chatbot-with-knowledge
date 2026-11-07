output "resource_group_name" {
  value = azurerm_resource_group.main.name
}

output "acr_name" {
  value = azurerm_container_registry.main.name
}

output "acr_login_server" {
  value = azurerm_container_registry.main.login_server
}

output "container_app_name" {
  value = azurerm_container_app.chatbot.name
}

output "container_app_url" {
  value = "https://${azurerm_container_app.chatbot.ingress[0].fqdn}"
}

output "key_vault_name" {
  value = azurerm_key_vault.main.name
}

output "knowledge_storage_account" {
  value = azurerm_storage_account.knowledge.name
}

