# =============================================================================
# OmniBridge Production Environment - Outputs
# =============================================================================

# --- VPC ---

output "vpc_id" {
  description = "ID of the production VPC"
  value       = module.vpc.vpc_id
}

output "nat_gateway_public_ip" {
  description = "Public IP of the NAT Gateway (for allowlisting outbound traffic)"
  value       = module.vpc.nat_gateway_public_ip
}

# --- Trading Applications ---

output "fix_acceptor_instance_id" {
  description = "EC2 instance ID of the FIX protocol engine"
  value       = module.fix_acceptor.instance_id
}

output "fix_acceptor_private_ip" {
  description = "Private IP of the FIX protocol engine"
  value       = module.fix_acceptor.private_ip
}

output "ouch_acceptor_instance_id" {
  description = "EC2 instance ID of the OUCH protocol engine"
  value       = module.ouch_acceptor.instance_id
}

output "ouch_acceptor_private_ip" {
  description = "Private IP of the OUCH protocol engine"
  value       = module.ouch_acceptor.private_ip
}

# --- Monitoring ---

output "monitoring_instance_id" {
  description = "EC2 instance ID of the monitoring stack"
  value       = module.monitoring.instance_id
}

output "monitoring_public_ip" {
  description = "Public IP of the monitoring instance"
  value       = module.monitoring.public_ip
}

output "grafana_url" {
  description = "URL to access the Grafana dashboard"
  value       = module.monitoring.grafana_url
}

output "prometheus_url" {
  description = "Internal URL for Prometheus (not publicly accessible)"
  value       = module.monitoring.prometheus_url
}

# --- Connection Info ---

output "connection_info" {
  description = "Summary of connection endpoints"
  value = {
    fix_endpoint  = "${module.fix_acceptor.private_ip}:9876"
    ouch_endpoint = "${module.ouch_acceptor.private_ip}:9200"
    fix_admin     = "${module.fix_acceptor.private_ip}:8081"
    ouch_admin    = "${module.ouch_acceptor.private_ip}:8082"
    grafana       = module.monitoring.grafana_url
  }
}
