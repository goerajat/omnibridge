# =============================================================================
# OmniBridge Monitoring Stack Module - Outputs
# =============================================================================

output "instance_id" {
  description = "ID of the monitoring EC2 instance"
  value       = aws_instance.monitoring.id
}

output "private_ip" {
  description = "Private IP address of the monitoring instance"
  value       = aws_instance.monitoring.private_ip
}

output "public_ip" {
  description = "Public IP address (Elastic IP) of the monitoring instance"
  value       = aws_eip.monitoring.public_ip
}

output "grafana_url" {
  description = "URL to access the Grafana dashboard"
  value       = "http://${aws_eip.monitoring.public_ip}:3001"
}

output "prometheus_url" {
  description = "URL to access Prometheus (internal use only)"
  value       = "http://${aws_instance.monitoring.private_ip}:9090"
}

output "instance_arn" {
  description = "ARN of the monitoring EC2 instance"
  value       = aws_instance.monitoring.arn
}
