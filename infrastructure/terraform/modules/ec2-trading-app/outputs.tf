# =============================================================================
# OmniBridge EC2 Trading App Module - Outputs
# =============================================================================

output "instance_id" {
  description = "ID of the trading application EC2 instance"
  value       = aws_instance.trading_app.id
}

output "private_ip" {
  description = "Private IP address of the trading application instance"
  value       = aws_instance.trading_app.private_ip
}

output "instance_arn" {
  description = "ARN of the trading application EC2 instance"
  value       = aws_instance.trading_app.arn
}

output "ebs_volume_id" {
  description = "ID of the attached GP3 EBS data volume"
  value       = aws_ebs_volume.trading_data.id
}

output "iam_role_arn" {
  description = "ARN of the IAM role attached to the instance"
  value       = aws_iam_role.trading_app.arn
}
