# =============================================================================
# OmniBridge Aeron Persistence Module
# =============================================================================
# Deploys an Aeron remote persistence store on EC2 with high-performance GP3
# EBS storage. Receives trading data over Aeron UDP and persists to Chronicle
# Queue for disaster-recovery replication.
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

resource "aws_iam_role" "aeron_persistence" {
  name = "omnibridge-${var.environment}-aeron-persistence-role"

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
    Name        = "omnibridge-${var.environment}-aeron-persistence-role"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

resource "aws_iam_role_policy" "s3_access" {
  name = "omnibridge-${var.environment}-aeron-persistence-s3-access"
  role = aws_iam_role.aeron_persistence.id

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
  name = "omnibridge-${var.environment}-aeron-persistence-cloudwatch"
  role = aws_iam_role.aeron_persistence.id

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

resource "aws_iam_instance_profile" "aeron_persistence" {
  name = "omnibridge-${var.environment}-aeron-persistence-profile"
  role = aws_iam_role.aeron_persistence.name
}

# -----------------------------------------------------------------------------
# EC2 Instance
# -----------------------------------------------------------------------------

resource "aws_instance" "aeron_persistence" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [var.security_group_id]
  iam_instance_profile   = aws_iam_instance_profile.aeron_persistence.name

  source_dest_check = true

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    encrypted             = true
    delete_on_termination = true

    tags = {
      Name = "omnibridge-${var.environment}-aeron-persistence-root"
    }
  }

  user_data = base64encode(templatefile("${path.module}/user_data.sh.tftpl", {
    app_version  = var.app_version
    s3_bucket    = var.s3_bucket
    environment  = var.environment
    ebs_device   = var.ebs_device_name
    engine_host  = var.engine_host
    publisher_id = var.publisher_id
  }))

  tags = {
    Name        = "omnibridge-${var.environment}-aeron-persistence"
    Environment = var.environment
    Project     = "omnibridge"
    AppType     = "aeron-persistence"
  }

  lifecycle {
    prevent_destroy = false
  }
}

# -----------------------------------------------------------------------------
# GP3 EBS Volume - High-performance storage for persistence data
# -----------------------------------------------------------------------------

resource "aws_ebs_volume" "persistence_data" {
  availability_zone = aws_instance.aeron_persistence.availability_zone
  size              = var.ebs_volume_size
  type              = "gp3"
  iops              = var.ebs_iops
  throughput        = var.ebs_throughput
  encrypted         = true

  tags = {
    Name        = "omnibridge-${var.environment}-aeron-persistence-data"
    Environment = var.environment
    Project     = "omnibridge"
    AppType     = "aeron-persistence"
  }
}

resource "aws_volume_attachment" "persistence_data" {
  device_name = var.ebs_device_name
  volume_id   = aws_ebs_volume.persistence_data.id
  instance_id = aws_instance.aeron_persistence.id

  # Allow time for graceful detach
  force_detach = false
}
