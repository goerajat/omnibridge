# AWS EC2 Monitoring Infrastructure Setup

This guide covers deploying the OmniBridge monitoring infrastructure on AWS EC2 using Terraform. The stack includes trading application instances (FIX and OUCH protocol engines) with Micrometer metrics, plus a monitoring instance running Prometheus, Grafana, and Alertmanager.

## Architecture Overview

```
VPC (10.0.0.0/16)
|
+-- Trading Subnet A (10.0.1.0/24, private, AZ-a)
|   +-- EC2: FIX Acceptor (c5.xlarge)
|       - FIX protocol port: 9876
|       - Admin/metrics port: 8081
|       - GP3 EBS: 100GB, 6000 IOPS
|
+-- Trading Subnet B (10.0.2.0/24, private, AZ-b)
|   +-- EC2: OUCH Acceptor (c5.xlarge)
|       - OUCH protocol port: 9200
|       - Admin/metrics port: 8082
|       - GP3 EBS: 100GB, 6000 IOPS
|
+-- Monitoring Subnet (10.0.10.0/24, public)
    +-- EC2: Monitoring Stack (t3.large)
        - Prometheus: 9090
        - Grafana: 3001
        - Alertmanager: 9093
        - Elastic IP for stable access
```

Trading instances sit in private subnets with outbound internet via a NAT Gateway. The monitoring instance is in a public subnet with an Elastic IP so Grafana is externally accessible. Prometheus scrapes the trading apps' `/api/metrics` endpoints over the private network.

### Security Groups

| Rule | Source | Destination | Ports |
|------|--------|-------------|-------|
| FIX protocol | Client CIDRs, Monitoring SG | Trading SG | 9876 |
| OUCH protocol | Client CIDRs, Monitoring SG | Trading SG | 9200 |
| Admin/metrics | Monitoring SG only | Trading SG | 8081-8082 |
| SSH | Bastion CIDRs | Trading SG, Monitoring SG | 22 |
| Grafana | Allowed CIDRs | Monitoring SG | 3001 |
| OmniView | Allowed CIDRs | Monitoring SG | 3000 |
| Prometheus | VPC CIDR only | Monitoring SG | 9090 |

## Prerequisites

### 1. Install Required Tools

- **Terraform >= 1.5**: https://developer.hashicorp.com/terraform/install
- **AWS CLI v2**: https://aws.amazon.com/cli/

Verify installations:

```bash
terraform --version   # >= 1.5.0
aws --version         # v2.x
```

### 2. Configure AWS Credentials

You need an IAM user or role with permissions for EC2, VPC, S3, DynamoDB, IAM, and EBS.

```bash
aws configure
# Enter: Access Key ID, Secret Access Key, Region (us-east-1), Output format (json)
```

### 3. Create an EC2 Key Pair

```bash
aws ec2 create-key-pair \
  --key-name omnibridge-key \
  --query 'KeyMaterial' \
  --output text > omnibridge-key.pem

chmod 400 omnibridge-key.pem
```

### 4. Create Terraform State Backend

Terraform state is stored remotely in S3 with DynamoDB locking to prevent concurrent modifications.

```bash
# S3 bucket for state storage
aws s3 mb s3://omnibridge-terraform-state --region us-east-1

# Enable versioning for state history
aws s3api put-bucket-versioning \
  --bucket omnibridge-terraform-state \
  --versioning-configuration Status=Enabled

# DynamoDB table for state locking
aws dynamodb create-table \
  --table-name omnibridge-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

### 5. Build and Upload Application Artifacts

```bash
# Build all modules
mvn install -DskipTests

# Create S3 bucket for artifacts
aws s3 mb s3://omnibridge-artifacts --region us-east-1

# Upload FIX distribution
aws s3 cp \
  apps/fix-samples/target/fix-samples-*-dist.tar.gz \
  s3://omnibridge-artifacts/omnibridge/production/

# Upload OUCH distribution
aws s3 cp \
  apps/ouch-samples/target/ouch-samples-*-dist.tar.gz \
  s3://omnibridge-artifacts/omnibridge/production/
```

## Terraform Module Structure

```
infrastructure/terraform/
+-- modules/
|   +-- vpc/                        # VPC, subnets, IGW, NAT, security groups
|   |   +-- main.tf
|   |   +-- variables.tf
|   |   +-- outputs.tf
|   +-- ec2-trading-app/            # Trading app EC2 + EBS + IAM
|   |   +-- main.tf
|   |   +-- user_data.sh.tftpl      # Bootstrap: Java 17, kernel tuning, app deploy
|   |   +-- variables.tf
|   |   +-- outputs.tf
|   +-- monitoring-stack/           # Monitoring EC2 + Docker Compose
|       +-- main.tf
|       +-- user_data.sh.tftpl      # Bootstrap: Docker, Prometheus, Grafana, Alertmanager
|       +-- variables.tf
|       +-- outputs.tf
+-- environments/
    +-- production/                 # Production config (c5.xlarge, 30d retention)
    |   +-- backend.tf
    |   +-- main.tf
    |   +-- variables.tf
    |   +-- outputs.tf
    +-- staging/                    # Staging config (t3.medium, 7d retention)
        +-- backend.tf
        +-- main.tf
        +-- variables.tf
        +-- outputs.tf
```

## Deploying Production

### Step 1: Configure Variables

```bash
cd infrastructure/terraform/environments/production
```

Create a `terraform.tfvars` file. **Do not commit this file** -- it contains sensitive values.

```hcl
# AWS
aws_region = "us-east-1"
ami_id     = "ami-0c7217cdde317cfec"  # Ubuntu 22.04 LTS in us-east-1
key_name   = "omnibridge-key"

# Application
app_version      = "1.0.2-SNAPSHOT"
s3_artifact_bucket = "omnibridge-artifacts"

# Network access control
client_cidrs  = ["10.0.0.0/8"]           # FIX/OUCH client IP ranges
ssh_cidrs     = ["203.0.113.10/32"]       # Your office IP for SSH
grafana_cidrs = ["203.0.113.10/32"]       # Your office IP for Grafana access

# Instance types
trading_instance_type    = "c5.xlarge"    # 4 vCPU, 8GB - CPU-optimized for trading
monitoring_instance_type = "t3.large"     # 2 vCPU, 8GB - sufficient for TSDB

# Monitoring
grafana_admin_password = "CHANGE_ME_TO_A_STRONG_PASSWORD"
slack_webhook_url      = "https://hooks.slack.com/services/YOUR/WEBHOOK/URL"
```

#### Variable Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `aws_region` | No | `us-east-1` | AWS region for all resources |
| `ami_id` | **Yes** | -- | AMI ID (Amazon Linux 2023 or Ubuntu 22.04) |
| `key_name` | **Yes** | -- | EC2 key pair name for SSH access |
| `app_version` | **Yes** | -- | Application version to deploy from S3 |
| `s3_artifact_bucket` | **Yes** | -- | S3 bucket with application distributions |
| `vpc_cidr` | No | `10.0.0.0/16` | VPC CIDR block |
| `client_cidrs` | No | `[]` | CIDRs allowed to connect to FIX/OUCH ports |
| `ssh_cidrs` | No | `[]` | CIDRs allowed SSH access |
| `grafana_cidrs` | No | `[]` | CIDRs allowed to access Grafana |
| `trading_instance_type` | No | `c5.xlarge` | EC2 type for trading apps |
| `monitoring_instance_type` | No | `t3.large` | EC2 type for monitoring |
| `grafana_admin_password` | **Yes** | -- | Grafana admin password (sensitive) |
| `slack_webhook_url` | No | `""` | Slack webhook for alert notifications |

### Step 2: Initialize Terraform

```bash
terraform init
```

This downloads the AWS provider and configures the S3 backend. You should see:

```
Terraform has been successfully initialized!
```

### Step 3: Review the Plan

```bash
terraform plan -out=tfplan
```

Review the output carefully. It will show the resources to be created:

- 1 VPC with 3 subnets
- 2 route tables (public + private)
- 1 Internet Gateway + 1 NAT Gateway
- 2 security groups (trading + monitoring)
- 3 EC2 instances (FIX acceptor, OUCH acceptor, monitoring)
- 2 EBS volumes (trading data storage)
- 1 Elastic IP (monitoring)
- 3 IAM roles with instance profiles

### Step 4: Apply

```bash
terraform apply tfplan
```

Deployment takes approximately 3-5 minutes. When complete, Terraform outputs the connection details:

```
Outputs:

fix_acceptor_private_ip = "10.0.1.x"
ouch_acceptor_private_ip = "10.0.2.x"
monitoring_public_ip = "54.x.x.x"
grafana_url = "http://54.x.x.x:3001"
prometheus_url = "http://10.0.10.x:9090"

connection_info = {
  fix_endpoint  = "10.0.1.x:9876"
  ouch_endpoint = "10.0.2.x:9200"
  fix_admin     = "10.0.1.x:8081"
  ouch_admin    = "10.0.2.x:8082"
  grafana       = "http://54.x.x.x:3001"
}
```

### Step 5: Verify Deployment

Allow 2-3 minutes for the EC2 user data scripts to complete bootstrapping.

**Check trading app health:**

```bash
# SSH to monitoring instance (it has access to trading subnet)
ssh -i omnibridge-key.pem ec2-user@<monitoring_public_ip>

# From monitoring instance, check trading apps
curl http://<fix_acceptor_private_ip>:8081/api/health
curl http://<ouch_acceptor_private_ip>:8082/api/health
```

**Check metrics endpoints:**

```bash
# From monitoring instance
curl http://<fix_acceptor_private_ip>:8081/api/metrics | head -50
```

You should see Prometheus-format metrics including JVM metrics (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`) and OmniBridge metrics (`omnibridge_messages_received_total`, `omnibridge_session_state`).

**Check Prometheus targets:**

```bash
curl http://<monitoring_private_ip>:9090/api/v1/targets | jq '.data.activeTargets[] | {instance, health}'
```

Both trading app targets should show `health: "up"`.

**Access Grafana:**

Open `http://<monitoring_public_ip>:3001` in a browser.

- Login: `admin` / `<grafana_admin_password from terraform.tfvars>`
- Three dashboards are auto-provisioned under the "OmniBridge" folder:
  1. **OmniBridge Overview** -- session health, throughput, latency, ring buffer
  2. **Session Deep Dive** -- per-session sequence numbers, heartbeats, message breakdown
  3. **JVM & Infrastructure** -- heap, GC, threads, CPU, event loop

**Check bootstrap logs (if something isn't working):**

```bash
# Trading app bootstrap log
ssh -i omnibridge-key.pem ec2-user@<fix_acceptor_ip>
sudo cat /var/log/omnibridge-bootstrap.log

# Monitoring stack bootstrap log
ssh -i omnibridge-key.pem ec2-user@<monitoring_public_ip>
sudo cat /var/log/omnibridge-monitoring-bootstrap.log
```

## Deploying Staging

The staging environment uses smaller instances and shorter metric retention to reduce costs.

```bash
cd infrastructure/terraform/environments/staging
```

Create `terraform.tfvars` with the same structure as production, then adjust:

```hcl
# Staging overrides
trading_instance_type    = "t3.medium"   # 2 vCPU, 4GB (vs c5.xlarge)
monitoring_instance_type = "t3.medium"   # Smaller monitoring
```

The staging `main.tf` automatically configures:
- EBS volumes: 50GB, 3000 IOPS, 125 MiB/s (vs 100GB, 6000, 400)
- Prometheus retention: 7 days (vs 30 days)
- Root volume: 50GB (vs 100GB)

```bash
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

## What Gets Bootstrapped

### Trading App Instances

The EC2 user data script (`user_data.sh.tftpl`) automatically:

1. Installs Amazon Corretto 17 (Java 17)
2. Mounts the GP3 EBS volume at `/opt/omnibridge`
3. Applies kernel tuning for low-latency trading:
   - TCP buffer sizes: 16MB max
   - TCP Nagle disabled (`tcp_nodelay=1`)
   - Reduced TCP keepalive (60s)
4. Downloads the application distribution from S3
5. Creates a systemd service (`omnibridge-fix` or `omnibridge-ouch`) with:
   - ZGC garbage collector
   - 2GB heap (`-Xms2g -Xmx2g`)
   - `AlwaysPreTouch` for pre-allocated memory
   - 65535 file descriptor limit

### Monitoring Instance

The monitoring user data script automatically:

1. Installs Docker and Docker Compose v2
2. Configures Prometheus with trading app targets for scraping
3. Creates alert rules (instance down, high latency, session disconnect, sequence gaps, high heap)
4. Configures Alertmanager with Slack routing (default + critical channels)
5. Provisions Grafana with Prometheus datasource
6. Starts the Docker Compose stack (Prometheus, Grafana on port 3001, Alertmanager)
7. Creates a systemd service for automatic restart on reboot

## Alerting

### Alert Rules

Prometheus alert rules are configured automatically during bootstrapping:

| Alert | Condition | Severity |
|-------|-----------|----------|
| `TradingInstanceDown` | Instance unreachable for 1m | Critical |
| `SessionDisconnected` | `session.connected == 0` for 30s | Critical |
| `HighFixLatency` | p99 > 1ms for 5m | Warning |
| `SequenceNumberGap` | Gap > 10 for 1m | Warning |
| `HighHeapUsage` | Heap > 85% for 5m | Warning |

A more comprehensive set of alert rules is available in the local Docker monitoring stack at `infrastructure/docker/prometheus/rules/omnibridge-alerts.yml` (15 rules covering ring buffer, rejects, heartbeats, GC pauses, direct buffers).

### Slack Notifications

Alerts route to two Slack channels:
- `#omnibridge-alerts` -- warnings and default notifications
- `#omnibridge-critical` -- critical alerts with 1-hour repeat interval

## Ongoing Management

### SSH Access

```bash
# Monitoring instance (public IP)
ssh -i omnibridge-key.pem ec2-user@<monitoring_public_ip>

# Trading instances (via monitoring as jump host)
ssh -i omnibridge-key.pem -J ec2-user@<monitoring_public_ip> ec2-user@<fix_private_ip>
```

### Service Management

```bash
# Trading app service
sudo systemctl status omnibridge-fix
sudo systemctl restart omnibridge-fix
sudo journalctl -u omnibridge-fix -f    # Follow logs

# Monitoring stack
sudo systemctl status omnibridge-monitoring
sudo systemctl restart omnibridge-monitoring
cd /opt/omnibridge-monitoring && sudo docker compose ps     # Container status
cd /opt/omnibridge-monitoring && sudo docker compose logs -f prometheus  # Prometheus logs
```

### Updating the Application

```bash
# Upload new artifact to S3
aws s3 cp apps/fix-samples/target/fix-samples-1.0.3-dist.tar.gz \
  s3://omnibridge-artifacts/omnibridge/production/

# Update Terraform variable and re-apply
cd infrastructure/terraform/environments/production
# Edit terraform.tfvars: app_version = "1.0.3"
terraform plan -out=tfplan
terraform apply tfplan
```

### Scaling

Modify `terraform.tfvars` and re-apply:

```hcl
# Upgrade trading instances
trading_instance_type = "c5.2xlarge"

# Increase monitoring storage
# (requires editing main.tf root_volume_size)
```

```bash
terraform plan -out=tfplan
terraform apply tfplan
```

## Tearing Down

```bash
cd infrastructure/terraform/environments/production
terraform destroy
```

Review the destruction plan and confirm. This removes all AWS resources including EC2 instances, EBS volumes, VPC, and security groups.

**Warning:** EBS volumes are destroyed. Back up any important data from `/opt/omnibridge/data` before destroying.

## Cost Estimate

### Production (monthly, us-east-1, on-demand)

| Resource | Spec | Est. Cost |
|----------|------|-----------|
| EC2 FIX Acceptor | c5.xlarge (4 vCPU, 8GB) | ~$124 |
| EC2 OUCH Acceptor | c5.xlarge (4 vCPU, 8GB) | ~$124 |
| EC2 Monitoring | t3.large (2 vCPU, 8GB) | ~$60 |
| EBS (3 volumes) | 250GB GP3 total | ~$25 |
| NAT Gateway | Data processing | ~$35 |
| Elastic IP | 1 EIP | ~$4 |
| S3 (artifacts + state) | < 1GB | ~$1 |
| **Total** | | **~$373/mo** |

### Staging (monthly)

| Resource | Spec | Est. Cost |
|----------|------|-----------|
| EC2 FIX Acceptor | t3.medium (2 vCPU, 4GB) | ~$30 |
| EC2 OUCH Acceptor | t3.medium (2 vCPU, 4GB) | ~$30 |
| EC2 Monitoring | t3.medium (2 vCPU, 4GB) | ~$30 |
| EBS (3 volumes) | 150GB GP3 total | ~$15 |
| NAT Gateway + EIP | | ~$39 |
| **Total** | | **~$144/mo** |

Use Reserved Instances or Savings Plans for 40-60% savings in production.

## Local Development Alternative

For local development and testing, use the Docker Compose monitoring stack instead of AWS:

```bash
# Start the local monitoring stack
cd infrastructure/docker
docker compose -f docker-compose.monitoring.yml up -d

# Run trading apps on the host
# FIX acceptor on port 8081, OUCH acceptor on port 8082

# Access:
# Grafana:      http://localhost:3001 (admin/admin)
# Prometheus:   http://localhost:9090
# Alertmanager: http://localhost:9093
```

The local stack uses `host.docker.internal` to scrape metrics from applications running on the host machine. See `infrastructure/docker/prometheus/prometheus.yml` for the scrape configuration.
