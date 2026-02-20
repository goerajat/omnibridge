# =============================================================================
# OmniBridge Staging Environment - Variables
# =============================================================================

# --- AWS Configuration ---

variable "aws_region" {
  description = "AWS region for resource deployment"
  type        = string
  default     = "us-east-1"
}

variable "ami_id" {
  description = "AMI ID for all EC2 instances (Amazon Linux 2023)"
  type        = string
}

variable "key_name" {
  description = "Name of the SSH key pair for EC2 instance access"
  type        = string
}

# --- Networking ---

variable "vpc_cidr" {
  description = "CIDR block for the staging VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "client_cidrs" {
  description = "CIDR blocks allowed to connect to FIX/OUCH trading ports"
  type        = list(string)
  default     = []
}

variable "ssh_cidrs" {
  description = "CIDR blocks allowed SSH access to instances"
  type        = list(string)
  default     = []
}

variable "grafana_cidrs" {
  description = "CIDR blocks allowed to access Grafana and OmniView dashboards"
  type        = list(string)
  default     = []
}

# --- Instance Types (cost-optimized for staging) ---

variable "trading_instance_type" {
  description = "EC2 instance type for trading applications (t3.medium for staging)"
  type        = string
  default     = "t3.medium"
}

variable "monitoring_instance_type" {
  description = "EC2 instance type for the monitoring stack"
  type        = string
  default     = "t3.medium"
}

# --- Application ---

variable "app_version" {
  description = "Version of the OmniBridge application to deploy"
  type        = string
}

variable "s3_artifact_bucket" {
  description = "S3 bucket containing application distribution artifacts"
  type        = string
}

# --- Monitoring ---

variable "grafana_admin_password" {
  description = "Admin password for the Grafana dashboard"
  type        = string
  sensitive   = true
}

variable "slack_webhook_url" {
  description = "Slack webhook URL for alert notifications (optional for staging)"
  type        = string
  sensitive   = true
  default     = ""
}
