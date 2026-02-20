# =============================================================================
# OmniBridge Monitoring Stack Module
# =============================================================================
# Deploys a monitoring instance running Docker Compose with Prometheus,
# Grafana, and Alertmanager. Automatically configures Prometheus to scrape
# metrics from trading application instances.
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

# -----------------------------------------------------------------------------
# IAM Role - allows monitoring instance to discover EC2 targets
# -----------------------------------------------------------------------------

resource "aws_iam_role" "monitoring" {
  name = "omnibridge-${var.environment}-monitoring-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "omnibridge-${var.environment}-monitoring-role"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

# EC2 describe permissions for Prometheus service discovery
resource "aws_iam_role_policy" "ec2_discovery" {
  name = "omnibridge-${var.environment}-monitoring-ec2-discovery"
  role = aws_iam_role.monitoring.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ec2:DescribeInstances",
          "ec2:DescribeTags"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "monitoring" {
  name = "omnibridge-${var.environment}-monitoring-profile"
  role = aws_iam_role.monitoring.name
}

# -----------------------------------------------------------------------------
# EC2 Instance
# -----------------------------------------------------------------------------

resource "aws_instance" "monitoring" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [var.security_group_id]
  iam_instance_profile   = aws_iam_instance_profile.monitoring.name

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    delete_on_termination = true

    tags = {
      Name = "omnibridge-${var.environment}-monitoring-root"
    }
  }

  user_data = base64encode(templatefile("${path.module}/user_data.sh.tftpl", {
    environment       = var.environment
    trading_app_ips   = var.trading_app_ips
    grafana_password  = var.grafana_admin_password
    slack_webhook_url = var.slack_webhook_url
    retention_days    = var.prometheus_retention_days
  }))

  tags = {
    Name        = "omnibridge-${var.environment}-monitoring"
    Environment = var.environment
    Project     = "omnibridge"
    Role        = "monitoring"
  }
}

# -----------------------------------------------------------------------------
# Elastic IP for stable public access to Grafana
# -----------------------------------------------------------------------------

resource "aws_eip" "monitoring" {
  instance = aws_instance.monitoring.id
  domain   = "vpc"

  tags = {
    Name        = "omnibridge-${var.environment}-monitoring-eip"
    Environment = var.environment
    Project     = "omnibridge"
  }
}
