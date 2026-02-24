# =============================================================================
# OmniBridge Aeron Persistence Module - Outputs
# =============================================================================

output "instance_id" {
  description = "ID of the Aeron persistence EC2 instance"
  value       = aws_instance.aeron_persistence.id
}

output "private_ip" {
  description = "Private IP address of the Aeron persistence instance"
  value       = aws_instance.aeron_persistence.private_ip
}

output "instance_arn" {
  description = "ARN of the Aeron persistence EC2 instance"
  value       = aws_instance.aeron_persistence.arn
}

output "ebs_volume_id" {
  description = "ID of the attached GP3 EBS data volume"
  value       = aws_ebs_volume.persistence_data.id
}

output "iam_role_arn" {
  description = "ARN of the IAM role attached to the instance"
  value       = aws_iam_role.aeron_persistence.arn
}
