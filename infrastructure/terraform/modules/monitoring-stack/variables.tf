# =============================================================================
# OmniBridge Monitoring Stack Module - Variables
# =============================================================================

variable "environment" {
  description = "Environment name (e.g., production, staging)"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type for the monitoring server"
  type        = string
  default     = "t3.large"
}

variable "ami_id" {
  description = "AMI ID for the EC2 instance (Amazon Linux 2023 recommended)"
  type        = string
}

variable "key_name" {
  description = "Name of the SSH key pair for instance access"
  type        = string
}

variable "subnet_id" {
  description = "ID of the monitoring subnet to launch the instance in"
  type        = string
}

variable "security_group_id" {
  description = "ID of the monitoring security group"
  type        = string
}

variable "trading_app_ips" {
  description = "List of private IP addresses of trading application instances for Prometheus scraping"
  type        = list(string)
}

variable "grafana_admin_password" {
  description = "Admin password for the Grafana dashboard"
  type        = string
  sensitive   = true
}

variable "slack_webhook_url" {
  description = "Slack webhook URL for Alertmanager notifications"
  type        = string
  sensitive   = true
  default     = ""
}

variable "prometheus_retention_days" {
  description = "Number of days to retain Prometheus metrics data"
  type        = number
  default     = 30

  validation {
    condition     = var.prometheus_retention_days >= 1 && var.prometheus_retention_days <= 365
    error_message = "Prometheus retention must be between 1 and 365 days."
  }
}

variable "root_volume_size" {
  description = "Size of the root EBS volume in GB (must accommodate Docker images and metrics storage)"
  type        = number
  default     = 100
}
