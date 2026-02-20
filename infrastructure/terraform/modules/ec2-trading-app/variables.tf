# =============================================================================
# OmniBridge EC2 Trading App Module - Variables
# =============================================================================

variable "environment" {
  description = "Environment name (e.g., production, staging)"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type. c5.xlarge recommended for production trading workloads."
  type        = string
  default     = "c5.xlarge"
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
  description = "ID of the subnet to launch the instance in"
  type        = string
}

variable "security_group_id" {
  description = "ID of the security group to attach to the instance"
  type        = string
}

variable "app_type" {
  description = "Type of trading application: 'fix' or 'ouch'"
  type        = string

  validation {
    condition     = contains(["fix", "ouch"], var.app_type)
    error_message = "App type must be either 'fix' or 'ouch'."
  }
}

variable "app_version" {
  description = "Version of the application to deploy (e.g., '1.0.1')"
  type        = string
}

variable "s3_bucket" {
  description = "S3 bucket name containing application distribution artifacts"
  type        = string
}

# --- EBS Configuration ---

variable "ebs_volume_size" {
  description = "Size of the GP3 EBS data volume in GB"
  type        = number
  default     = 100

  validation {
    condition     = var.ebs_volume_size >= 20 && var.ebs_volume_size <= 16384
    error_message = "EBS volume size must be between 20 and 16384 GB."
  }
}

variable "ebs_iops" {
  description = "Provisioned IOPS for the GP3 EBS volume (3000-16000)"
  type        = number
  default     = 6000

  validation {
    condition     = var.ebs_iops >= 3000 && var.ebs_iops <= 16000
    error_message = "GP3 IOPS must be between 3000 and 16000."
  }
}

variable "ebs_throughput" {
  description = "Provisioned throughput for the GP3 EBS volume in MiB/s (125-1000)"
  type        = number
  default     = 400

  validation {
    condition     = var.ebs_throughput >= 125 && var.ebs_throughput <= 1000
    error_message = "GP3 throughput must be between 125 and 1000 MiB/s."
  }
}

variable "ebs_device_name" {
  description = "Device name for the EBS volume attachment"
  type        = string
  default     = "/dev/xvdf"
}
