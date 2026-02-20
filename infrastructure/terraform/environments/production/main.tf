# =============================================================================
# OmniBridge Production Environment
# =============================================================================
# Orchestrates all infrastructure modules for a production deployment:
#   - VPC with trading and monitoring network tiers
#   - FIX protocol engine instance (c5.xlarge)
#   - OUCH protocol engine instance (c5.xlarge)
#   - Monitoring stack (Prometheus, Grafana, Alertmanager)
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
      Environment = "production"
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

  environment            = "production"
  vpc_cidr               = var.vpc_cidr
  trading_subnet_a_cidr  = "10.0.1.0/24"
  trading_subnet_b_cidr  = "10.0.2.0/24"
  monitoring_subnet_cidr = "10.0.10.0/24"
  client_cidrs           = var.client_cidrs
  ssh_cidrs              = var.ssh_cidrs
  grafana_cidrs          = var.grafana_cidrs
}

# -----------------------------------------------------------------------------
# FIX Protocol Engine
# -----------------------------------------------------------------------------

module "fix_acceptor" {
  source = "../../modules/ec2-trading-app"

  environment       = "production"
  instance_type     = var.trading_instance_type
  ami_id            = var.ami_id
  key_name          = var.key_name
  subnet_id         = module.vpc.trading_subnet_a_id
  security_group_id = module.vpc.trading_security_group_id
  app_type          = "fix"
  app_version       = var.app_version
  s3_bucket         = var.s3_artifact_bucket
  ebs_volume_size   = 100
  ebs_iops          = 6000
  ebs_throughput    = 400
}

# -----------------------------------------------------------------------------
# OUCH Protocol Engine
# -----------------------------------------------------------------------------

module "ouch_acceptor" {
  source = "../../modules/ec2-trading-app"

  environment       = "production"
  instance_type     = var.trading_instance_type
  ami_id            = var.ami_id
  key_name          = var.key_name
  subnet_id         = module.vpc.trading_subnet_b_id
  security_group_id = module.vpc.trading_security_group_id
  app_type          = "ouch"
  app_version       = var.app_version
  s3_bucket         = var.s3_artifact_bucket
  ebs_volume_size   = 100
  ebs_iops          = 6000
  ebs_throughput    = 400
}

# -----------------------------------------------------------------------------
# Monitoring Stack (Prometheus + Grafana + Alertmanager)
# -----------------------------------------------------------------------------

module "monitoring" {
  source = "../../modules/monitoring-stack"

  environment            = "production"
  instance_type          = var.monitoring_instance_type
  ami_id                 = var.ami_id
  key_name               = var.key_name
  subnet_id              = module.vpc.monitoring_subnet_id
  security_group_id      = module.vpc.monitoring_security_group_id
  grafana_admin_password = var.grafana_admin_password
  slack_webhook_url      = var.slack_webhook_url

  # Pass trading app IPs for Prometheus target configuration
  trading_app_ips = [
    module.fix_acceptor.private_ip,
    module.ouch_acceptor.private_ip,
  ]

  # Production: 30-day metric retention
  prometheus_retention_days = 30
  root_volume_size          = 100
}
