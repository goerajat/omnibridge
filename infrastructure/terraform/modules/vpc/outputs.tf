# =============================================================================
# OmniBridge VPC Module - Outputs
# =============================================================================

output "vpc_id" {
  description = "ID of the OmniBridge VPC"
  value       = aws_vpc.omnibridge.id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC"
  value       = aws_vpc.omnibridge.cidr_block
}

output "trading_subnet_a_id" {
  description = "ID of Trading Subnet A"
  value       = aws_subnet.trading_a.id
}

output "trading_subnet_b_id" {
  description = "ID of Trading Subnet B"
  value       = aws_subnet.trading_b.id
}

output "monitoring_subnet_id" {
  description = "ID of the Monitoring Subnet"
  value       = aws_subnet.monitoring.id
}

output "trading_security_group_id" {
  description = "ID of the trading application security group"
  value       = aws_security_group.trading.id
}

output "monitoring_security_group_id" {
  description = "ID of the monitoring stack security group"
  value       = aws_security_group.monitoring.id
}

output "internet_gateway_id" {
  description = "ID of the Internet Gateway"
  value       = aws_internet_gateway.omnibridge.id
}

output "nat_gateway_id" {
  description = "ID of the NAT Gateway"
  value       = aws_nat_gateway.omnibridge.id
}

output "nat_gateway_public_ip" {
  description = "Public IP of the NAT Gateway"
  value       = aws_eip.nat.public_ip
}
