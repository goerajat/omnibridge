# =============================================================================
# OmniBridge Staging Environment
# =============================================================================
# Mirrors the production architecture with smaller, cost-optimized instances
# and shorter data retention. Used for integration testing and validation
# before production deployment.
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = "staging"
      Project     = "omnibridge"
      ManagedBy   = "terraform"
    }
  }
}

# -----------------------------------------------------------------------------
# VPC
# -----------------------------------------------------------------------------

module "vpc" {
  source = "../../modules/vpc"

  environment            = "staging"
  vpc_cidr               = var.vpc_cidr
  trading_subnet_a_cidr  = "10.0.1.0/24"
  trading_subnet_b_cidr  = "10.0.2.0/24"
  monitoring_subnet_cidr = "10.0.10.0/24"
  client_cidrs           = var.client_cidrs
  ssh_cidrs              = var.ssh_cidrs
  grafana_cidrs          = var.grafana_cidrs
}

# -----------------------------------------------------------------------------
# FIX Protocol Engine (smaller instance for staging)
# -----------------------------------------------------------------------------

module "fix_acceptor" {
  source = "../../modules/ec2-trading-app"

  environment       = "staging"
  instance_type     = var.trading_instance_type
  ami_id            = var.ami_id
  key_name          = var.key_name
  subnet_id         = module.vpc.trading_subnet_a_id
  security_group_id = module.vpc.trading_security_group_id
  app_type          = "fix"
  app_version       = var.app_version
  s3_bucket         = var.s3_artifact_bucket

  # Reduced EBS specs for staging
  ebs_volume_size = 50
  ebs_iops        = 3000
  ebs_throughput  = 125
}

# -----------------------------------------------------------------------------
# OUCH Protocol Engine (smaller instance for staging)
# -----------------------------------------------------------------------------

module "ouch_acceptor" {
  source = "../../modules/ec2-trading-app"

  environment       = "staging"
  instance_type     = var.trading_instance_type
  ami_id            = var.ami_id
  key_name          = var.key_name
  subnet_id         = module.vpc.trading_subnet_b_id
  security_group_id = module.vpc.trading_security_group_id
  app_type          = "ouch"
  app_version       = var.app_version
  s3_bucket         = var.s3_artifact_bucket

  # Reduced EBS specs for staging
  ebs_volume_size = 50
  ebs_iops        = 3000
  ebs_throughput  = 125
}

# -----------------------------------------------------------------------------
# Monitoring Stack (smaller instance, shorter retention)
# -----------------------------------------------------------------------------

module "monitoring" {
  source = "../../modules/monitoring-stack"

  environment            = "staging"
  instance_type          = var.monitoring_instance_type
  ami_id                 = var.ami_id
  key_name               = var.key_name
  subnet_id              = module.vpc.monitoring_subnet_id
  security_group_id      = module.vpc.monitoring_security_group_id
  grafana_admin_password = var.grafana_admin_password
  slack_webhook_url      = var.slack_webhook_url

  trading_app_ips = [
    module.fix_acceptor.private_ip,
    module.ouch_acceptor.private_ip,
  ]

  # Staging: 7-day metric retention, smaller volume
  prometheus_retention_days = 7
  root_volume_size          = 50
}
