variable "subscription_id" {

}
  default     = 30
  type        = number
  description = "Log Analytics retention in days."
variable "log_retention_days" {

}
  default     = 3
  type        = number
  description = "Maximum replicas."
variable "max_replicas" {

}
  default     = 1
  type        = number
  description = "Minimum replicas (1 avoids cold starts)."
variable "min_replicas" {

}
  default     = 80
  type        = number
  description = "Port the container listens on. Use 8080 for the real app image."
variable "container_port" {

}
  default     = "mcr.microsoft.com/k8se/quickstart:latest"
  type        = string
  description = "Container image to deploy. The default is a placeholder used until the first image is pushed to ACR."
variable "container_image" {

}
  sensitive   = true
  type        = string
  description = "External OpenAI API key. Stored in Key Vault. Pass via TF_VAR_openai_api_key, never commit."
variable "openai_api_key" {

}
  default     = "chatbot"
  type        = string
  description = "Prefix for resource names."
variable "name_prefix" {

}
  default     = "dev"
  type        = string
  description = "Environment name (dev or prod)."
variable "environment" {

}
  default     = "westeurope"
  type        = string
  description = "Azure region (EU data residency)."
variable "location" {

}
  type        = string
  description = "Azure subscription ID."
