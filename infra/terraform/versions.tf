terraform {
  required_version = ">= 1.6.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Configure remote state per environment, for example:
  # terraform init -backend-config="resource_group_name=..." ...
  backend "azurerm" {}
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

