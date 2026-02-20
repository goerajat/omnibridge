# =============================================================================
# OmniBridge VPC Module - Variables
# =============================================================================

variable "environment" {
  description = "Environment name (e.g., production, staging)"
  type        = string

  validation {
    condition     = contains(["production", "staging", "development"], var.environment)
    error_message = "Environment must be one of: production, staging, development."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "VPC CIDR must be a valid IPv4 CIDR block."
  }
}

variable "trading_subnet_a_cidr" {
  description = "CIDR block for Trading Subnet A (AZ-a)"
  type        = string
  default     = "10.0.1.0/24"
}

variable "trading_subnet_b_cidr" {
  description = "CIDR block for Trading Subnet B (AZ-b)"
  type        = string
  default     = "10.0.2.0/24"
}

variable "monitoring_subnet_cidr" {
  description = "CIDR block for the Monitoring Subnet"
  type        = string
  default     = "10.0.10.0/24"
}

variable "client_cidrs" {
  description = "List of CIDR blocks allowed to connect to FIX/OUCH trading ports"
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for cidr in var.client_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All client CIDRs must be valid IPv4 CIDR blocks."
  }
}

variable "ssh_cidrs" {
  description = "List of CIDR blocks allowed SSH access (bastion/management)"
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for cidr in var.ssh_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All SSH CIDRs must be valid IPv4 CIDR blocks."
  }
}

variable "grafana_cidrs" {
  description = "List of CIDR blocks allowed to access Grafana and OmniView dashboards"
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for cidr in var.grafana_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All Grafana CIDRs must be valid IPv4 CIDR blocks."
  }
}
