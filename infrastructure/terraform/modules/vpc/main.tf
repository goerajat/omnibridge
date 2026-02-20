# =============================================================================
# OmniBridge VPC Module
# =============================================================================
# Creates a VPC with dedicated subnets for trading applications and monitoring,
# along with security groups that enforce network isolation between tiers.
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
# Data Sources
# -----------------------------------------------------------------------------

data "aws_availability_zones" "available" {
  state = "available"
}

# -----------------------------------------------------------------------------
# VPC
# -----------------------------------------------------------------------------

resource "aws_vpc" "omnibridge" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name        = "omnibridge-${var.environment}-vpc"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

# -----------------------------------------------------------------------------
# Internet Gateway
# -----------------------------------------------------------------------------

resource "aws_internet_gateway" "omnibridge" {
  vpc_id = aws_vpc.omnibridge.id

  tags = {
    Name        = "omnibridge-${var.environment}-igw"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

# -----------------------------------------------------------------------------
# Trading Subnets (Private)
# -----------------------------------------------------------------------------
# Trading subnets host FIX and OUCH protocol engine instances.
# Placed in separate AZs for high availability.

resource "aws_subnet" "trading_a" {
  vpc_id            = aws_vpc.omnibridge.id
  cidr_block        = var.trading_subnet_a_cidr
  availability_zone = data.aws_availability_zones.available.names[0]

  tags = {
    Name        = "omnibridge-${var.environment}-trading-a"
    Environment = var.environment
    Project     = "omnibridge"
    Tier        = "trading"
  }
}

resource "aws_subnet" "trading_b" {
  vpc_id            = aws_vpc.omnibridge.id
  cidr_block        = var.trading_subnet_b_cidr
  availability_zone = data.aws_availability_zones.available.names[1]

  tags = {
    Name        = "omnibridge-${var.environment}-trading-b"
    Environment = var.environment
    Project     = "omnibridge"
    Tier        = "trading"
  }
}

# -----------------------------------------------------------------------------
# Monitoring Subnet (Public)
# -----------------------------------------------------------------------------
# Monitoring subnet hosts Prometheus, Grafana, and OmniView.
# Public IP assignment allows external access to Grafana dashboards.

resource "aws_subnet" "monitoring" {
  vpc_id                  = aws_vpc.omnibridge.id
  cidr_block              = var.monitoring_subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = {
    Name        = "omnibridge-${var.environment}-monitoring"
    Environment = var.environment
    Project     = "omnibridge"
    Tier        = "monitoring"
  }
}

# -----------------------------------------------------------------------------
# NAT Gateway (for trading subnet outbound access)
# -----------------------------------------------------------------------------
# Trading instances need outbound internet for package updates and S3 access.

resource "aws_eip" "nat" {
  domain = "vpc"

  tags = {
    Name        = "omnibridge-${var.environment}-nat-eip"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

resource "aws_nat_gateway" "omnibridge" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.monitoring.id

  tags = {
    Name        = "omnibridge-${var.environment}-nat"
    Environment = var.environment
    Project     = "omnibridge"
  }

  depends_on = [aws_internet_gateway.omnibridge]
}

# -----------------------------------------------------------------------------
# Route Tables
# -----------------------------------------------------------------------------

# Public route table (monitoring subnet) - routes to Internet Gateway
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.omnibridge.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.omnibridge.id
  }

  tags = {
    Name        = "omnibridge-${var.environment}-public-rt"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

resource "aws_route_table_association" "monitoring" {
  subnet_id      = aws_subnet.monitoring.id
  route_table_id = aws_route_table.public.id
}

# Private route table (trading subnets) - routes to NAT Gateway
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.omnibridge.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.omnibridge.id
  }

  tags = {
    Name        = "omnibridge-${var.environment}-private-rt"
    Environment = var.environment
    Project     = "omnibridge"
  }
}

resource "aws_route_table_association" "trading_a" {
  subnet_id      = aws_subnet.trading_a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "trading_b" {
  subnet_id      = aws_subnet.trading_b.id
  route_table_id = aws_route_table.private.id
}

# -----------------------------------------------------------------------------
# Security Group: Trading
# -----------------------------------------------------------------------------
# Controls access to FIX/OUCH protocol engine instances.
# Only monitoring instances and bastion hosts can reach trading apps.

resource "aws_security_group" "trading" {
  name_prefix = "omnibridge-${var.environment}-trading-"
  description = "Security group for OmniBridge trading application instances"
  vpc_id      = aws_vpc.omnibridge.id

  tags = {
    Name        = "omnibridge-${var.environment}-trading-sg"
    Environment = var.environment
    Project     = "omnibridge"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# FIX protocol port (9876) - from monitoring SG
resource "aws_vpc_security_group_ingress_rule" "trading_fix_from_monitoring" {
  security_group_id            = aws_security_group.trading.id
  description                  = "FIX protocol from monitoring subnet"
  from_port                    = 9876
  to_port                      = 9876
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.monitoring.id
}

# FIX protocol port (9876) - from client CIDRs
resource "aws_vpc_security_group_ingress_rule" "trading_fix_from_clients" {
  for_each = toset(var.client_cidrs)

  security_group_id = aws_security_group.trading.id
  description       = "FIX protocol from client CIDR ${each.value}"
  from_port         = 9876
  to_port           = 9876
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# OUCH protocol port (9200) - from monitoring SG
resource "aws_vpc_security_group_ingress_rule" "trading_ouch_from_monitoring" {
  security_group_id            = aws_security_group.trading.id
  description                  = "OUCH protocol from monitoring subnet"
  from_port                    = 9200
  to_port                      = 9200
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.monitoring.id
}

# OUCH protocol port (9200) - from client CIDRs
resource "aws_vpc_security_group_ingress_rule" "trading_ouch_from_clients" {
  for_each = toset(var.client_cidrs)

  security_group_id = aws_security_group.trading.id
  description       = "OUCH protocol from client CIDR ${each.value}"
  from_port         = 9200
  to_port           = 9200
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# Admin ports (8081-8082) - from monitoring SG only
resource "aws_vpc_security_group_ingress_rule" "trading_admin_from_monitoring" {
  security_group_id            = aws_security_group.trading.id
  description                  = "Admin ports from monitoring subnet"
  from_port                    = 8081
  to_port                      = 8082
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.monitoring.id
}

# Prometheus metrics scraping (default Micrometer port 8080) - from monitoring SG
resource "aws_vpc_security_group_ingress_rule" "trading_metrics_from_monitoring" {
  security_group_id            = aws_security_group.trading.id
  description                  = "Prometheus metrics scraping from monitoring"
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.monitoring.id
}

# SSH access from bastion/management CIDRs
resource "aws_vpc_security_group_ingress_rule" "trading_ssh" {
  for_each = toset(var.ssh_cidrs)

  security_group_id = aws_security_group.trading.id
  description       = "SSH from ${each.value}"
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# Allow all outbound traffic from trading instances
resource "aws_vpc_security_group_egress_rule" "trading_all_outbound" {
  security_group_id = aws_security_group.trading.id
  description       = "All outbound traffic"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# -----------------------------------------------------------------------------
# Security Group: Monitoring
# -----------------------------------------------------------------------------
# Controls access to Prometheus, Grafana, and OmniView instances.

resource "aws_security_group" "monitoring" {
  name_prefix = "omnibridge-${var.environment}-monitoring-"
  description = "Security group for OmniBridge monitoring stack instances"
  vpc_id      = aws_vpc.omnibridge.id

  tags = {
    Name        = "omnibridge-${var.environment}-monitoring-sg"
    Environment = var.environment
    Project     = "omnibridge"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Grafana (3001) - from allowed CIDRs
resource "aws_vpc_security_group_ingress_rule" "monitoring_grafana" {
  for_each = toset(var.grafana_cidrs)

  security_group_id = aws_security_group.monitoring.id
  description       = "Grafana from ${each.value}"
  from_port         = 3001
  to_port           = 3001
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# Prometheus (9090) - internal VPC access only
resource "aws_vpc_security_group_ingress_rule" "monitoring_prometheus_internal" {
  security_group_id = aws_security_group.monitoring.id
  description       = "Prometheus internal access"
  from_port         = 9090
  to_port           = 9090
  ip_protocol       = "tcp"
  cidr_ipv4         = var.vpc_cidr
}

# OmniView (3000) - from allowed CIDRs
resource "aws_vpc_security_group_ingress_rule" "monitoring_omniview" {
  for_each = toset(var.grafana_cidrs)

  security_group_id = aws_security_group.monitoring.id
  description       = "OmniView from ${each.value}"
  from_port         = 3000
  to_port           = 3000
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# SSH access from bastion/management CIDRs
resource "aws_vpc_security_group_ingress_rule" "monitoring_ssh" {
  for_each = toset(var.ssh_cidrs)

  security_group_id = aws_security_group.monitoring.id
  description       = "SSH from ${each.value}"
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
  cidr_ipv4         = each.value
}

# Outbound to trading subnets (protocol, admin, metrics ports)
resource "aws_vpc_security_group_egress_rule" "monitoring_all_outbound" {
  security_group_id = aws_security_group.monitoring.id
  description       = "All outbound traffic"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
