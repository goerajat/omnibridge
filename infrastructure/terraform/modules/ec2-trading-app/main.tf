# =============================================================================
# OmniBridge EC2 Trading App Module
# =============================================================================
# Deploys a FIX or OUCH protocol engine instance on EC2 with high-performance
# GP3 EBS storage. The application is pulled from S3 during bootstrapping.
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
# IAM Role - allows EC2 to pull artifacts from S3
# -----------------------------------------------------------------------------

resource "aws_iam_role" "trading_app" {
  name = "omnibridge-${var.environment}-${var.app_type}-role"

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
    Name        = "omnibridge-${var.environment}-${var.app_type}-role"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

resource "aws_iam_role_policy" "s3_access" {
  name = "omnibridge-${var.environment}-${var.app_type}-s3-access"
  role = aws_iam_role.trading_app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::${var.s3_bucket}",
          "arn:aws:s3:::${var.s3_bucket}/*"
        ]
      }
    ]
  })
}

# Allow CloudWatch metrics publishing for Micrometer
resource "aws_iam_role_policy" "cloudwatch_access" {
  name = "omnibridge-${var.environment}-${var.app_type}-cloudwatch"
  role = aws_iam_role.trading_app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricData"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = "OmniBridge/${var.environment}"
          }
        }
      }
    ]
  })
}

resource "aws_iam_instance_profile" "trading_app" {
  name = "omnibridge-${var.environment}-${var.app_type}-profile"
  role = aws_iam_role.trading_app.name
}

# -----------------------------------------------------------------------------
# EC2 Instance
# -----------------------------------------------------------------------------

resource "aws_instance" "trading_app" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [var.security_group_id]
  iam_instance_profile   = aws_iam_instance_profile.trading_app.name

  # Disable source/dest check for trading traffic
  source_dest_check = true

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    encrypted             = true
    delete_on_termination = true

    tags = {
      Name = "omnibridge-${var.environment}-${var.app_type}-root"
    }
  }

  user_data = base64encode(templatefile("${path.module}/user_data.sh.tftpl", {
    app_type    = var.app_type
    app_version = var.app_version
    s3_bucket   = var.s3_bucket
    environment = var.environment
    ebs_device  = var.ebs_device_name
    admin_port  = var.app_type == "fix" ? "8081" : "8082"
  }))

  tags = {
    Name        = "omnibridge-${var.environment}-${var.app_type}"
    Environment = var.environment
    Project     = "omnibridge"
    AppType     = var.app_type
  }

  lifecycle {
    # Prevent accidental destruction of trading instances
    prevent_destroy = false
  }
}

# -----------------------------------------------------------------------------
# GP3 EBS Volume - High-performance storage for trading data and logs
# -----------------------------------------------------------------------------

resource "aws_ebs_volume" "trading_data" {
  availability_zone = aws_instance.trading_app.availability_zone
  size              = var.ebs_volume_size
  type              = "gp3"
  iops              = var.ebs_iops
  throughput        = var.ebs_throughput
  encrypted         = true

  tags = {
    Name        = "omnibridge-${var.environment}-${var.app_type}-data"
    Environment = var.environment
    Project     = "omnibridge"
    AppType     = var.app_type
  }
}

resource "aws_volume_attachment" "trading_data" {
  device_name = var.ebs_device_name
  volume_id   = aws_ebs_volume.trading_data.id
  instance_id = aws_instance.trading_app.id

  # Allow time for graceful detach
  force_detach = false
}
