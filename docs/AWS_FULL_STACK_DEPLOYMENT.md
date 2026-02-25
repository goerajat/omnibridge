# AWS Full Stack Deployment Guide

Complete guide for building and deploying the OmniBridge exchange simulator, FIX initiator, Aeron remote persistence store, and monitoring infrastructure on AWS EC2.

## Architecture

```
VPC (10.0.0.0/16)
|
+-- Trading Subnet A (10.0.1.0/24, private, AZ-a)
|   |
|   +-- EC2: Exchange Simulator (c5.xlarge)          ──── Primary Trading Engine
|   |   - FIX: 9876 (5 sessions, CompID-routed)
|   |   - OUCH 4.2: 9200, OUCH 5.0: 9201
|   |   - iLink3: 9300, Optiq: 9400, Pillar: 9500
|   |   - Admin/metrics: 8080
|   |   - Aeron data out: 40456 (UDP → Subnet C)
|   |   - Aeron control out: 40457 (UDP → Subnet C)
|   |   - Aeron replay in: 40458 (UDP)
|   |   - Local Chronicle cache: /opt/exchange-simulator/data/local-cache
|   |   - GP3 EBS: 100GB, 6000 IOPS
|   |
|   +-- EC2: FIX Initiator (t3.medium)               ──── Trading Client
|       - FIX client → Simulator:9876
|       - Admin/metrics: 8082
|       - Modes: interactive, auto, latency
|       - GP3 EBS: 50GB
|
+-- Trading Subnet B (10.0.2.0/24, private, AZ-b)    ──── (reserved for HA/additional engines)
|
+-- Persistence Subnet (10.0.3.0/24, private, AZ-b)
|   |
|   +-- EC2: Aeron Remote Store (t3.large)            ──── Off-Host Durability
|       - Aeron data in: 40456 (UDP)
|       - Aeron control in: 40457 (UDP)
|       - Aeron replay out: 40458 (UDP → Subnet A)
|       - Chronicle store: /opt/aeron-store/data/remote-store
|       - GP3 EBS: 200GB, 6000 IOPS (high write volume)
|
+-- Monitoring Subnet (10.0.10.0/24, public)
    |
    +-- EC2: Monitoring + OmniView (t3.large)         ──── Observability
        - Prometheus: 9090 (scrapes all admin ports)
        - Grafana: 3001 (Elastic IP for external access)
        - Alertmanager: 9093
        - OmniView: 3000
        - Elastic IP for stable access
```

### Data Flow

```
FIX Client ──FIX 9876──► Exchange Simulator ──Aeron UDP──► Aeron Remote Store
                              │                                  │
                              │ write-through                    │ durable Chronicle
                              ▼                                  ▼
                         Local Chronicle              Remote Chronicle Queue
                         (fast reads)                 (disaster recovery)
                              │
              ◄───────────────┘
         Reads served locally
         (zero network latency)

Recovery: Engine restarts → sends ReplayRequest → Remote Store streams entries back
```

### Port Assignments

| Instance | Port | Protocol | Purpose |
|----------|------|----------|---------|
| Exchange Simulator | 9876 | TCP | FIX sessions (5 sessions, shared port) |
| Exchange Simulator | 9200 | TCP | OUCH 4.2 sessions |
| Exchange Simulator | 9201 | TCP | OUCH 5.0 session |
| Exchange Simulator | 9300 | TCP | iLink3 session |
| Exchange Simulator | 9400 | TCP | Optiq session |
| Exchange Simulator | 9500 | TCP | Pillar session |
| Exchange Simulator | 8080 | HTTP | Admin API + Prometheus metrics |
| Exchange Simulator | 40456 | UDP | Aeron data out (log entry replication) |
| Exchange Simulator | 40457 | UDP | Aeron control out (replay requests, heartbeats) |
| Exchange Simulator | 40458 | UDP | Aeron replay in (replay responses) |
| FIX Initiator | 8082 | HTTP | Admin API + Prometheus metrics |
| Aeron Remote Store | 40456 | UDP | Aeron data in (receives log entries) |
| Aeron Remote Store | 40457 | UDP | Aeron control in (receives replay requests) |
| Aeron Remote Store | 40458 | UDP | Aeron replay out (sends replay responses) |
| Monitoring | 9090 | HTTP | Prometheus |
| Monitoring | 3001 | HTTP | Grafana |
| Monitoring | 9093 | HTTP | Alertmanager |
| Monitoring | 3000 | HTTP | OmniView |

### Security Groups

| Rule | Source | Destination | Ports |
|------|--------|-------------|-------|
| FIX/OUCH/SBE protocols | Client CIDRs, VPC CIDR | Trading SG | 9200-9500, 9876 |
| Admin/metrics | Monitoring SG, VPC CIDR | Trading SG | 8080-8082 |
| Aeron data/control | Trading SG | Persistence SG | 40456-40457 (UDP) |
| Aeron replay | Persistence SG | Trading SG | 40458 (UDP) |
| SSH | Bastion CIDRs | All SGs | 22 |
| Grafana/OmniView | Allowed CIDRs | Monitoring SG | 3000-3001 |
| Prometheus | VPC CIDR only | Monitoring SG | 9090 |

---

## Prerequisites

### 1. Install Required Tools

```bash
terraform --version   # >= 1.5.0
aws --version         # v2.x
java -version         # 17+
mvn --version         # 3.8+
jq --version          # 1.6+ (optional, for formatted deploy output)
```

**jq** is used by `terraform-deploy.sh` to display the infrastructure summary after a deploy. If not installed, the script still works but prints raw output instead.

```bash
# Linux (apt)
sudo apt install -y jq

# macOS
brew install jq

# Windows (Cygwin/MSYS2) — download the static binary
curl -Lo /usr/local/bin/jq.exe https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-windows-amd64.exe
chmod +x /usr/local/bin/jq.exe
```

### 2. Configure AWS Credentials

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

```bash
aws s3 mb s3://omnibridge-terraform-state --region us-east-1

aws s3api put-bucket-versioning \
  --bucket omnibridge-terraform-state \
  --versioning-configuration Status=Enabled

aws dynamodb create-table \
  --table-name omnibridge-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

---

## Build

All components are built from the project root.

### Step 1: Build All Modules

```bash
mvn install -DskipTests
```

This produces the following distribution packages:

| Component | Artifact Path | Contents |
|-----------|--------------|----------|
| Exchange Simulator | `apps/exchange-simulator/target/exchange-simulator-*-dist.tar.gz` | bin/, lib/, conf/, data/ |
| FIX Samples (Initiator + Acceptor) | `apps/fix-samples/target/fix-samples-*-dist.tar.gz` | bin/, lib/, conf/ |
| Aeron Remote Store | `apps/aeron-remote-store/target/aeron-remote-store-*-dist.tar.gz` | bin/, lib/, conf/, data/ |
| Log Viewer | `apps/log-viewer/target/log-viewer-*-dist.tar.gz` | bin/, lib/, conf/ |
| OmniView | `omniview/target/omniview-*-dist.tar.gz` | bin/, lib/, conf/ |

### Step 2: Upload Artifacts to S3

```bash
# Create S3 bucket for artifacts (first time only)
aws s3 mb s3://omnibridge-artifacts --region us-east-1

# Upload all artifacts (version auto-detected from pom.xml)
./scripts/deploy/upload-artifacts.sh

# Or upload with an explicit version
./scripts/deploy/upload-artifacts.sh -v 1.0.2-SNAPSHOT

# Or upload specific components only
./scripts/deploy/upload-artifacts.sh aeron-remote-store log-viewer

# Dry run to preview what would be uploaded
./scripts/deploy/upload-artifacts.sh -n
```

---

## Automated Deployment (Recommended)

Three scripts automate the full lifecycle: infrastructure provisioning, application setup, and service management.

### Step 1: Provision Infrastructure

```bash
# First-time deploy: init + plan + apply, save outputs to tf-outputs.json
./scripts/deploy/terraform-deploy.sh

# Full redeploy: destroy existing + re-provision
./scripts/deploy/terraform-deploy.sh -d

# Override the app version from terraform.tfvars
./scripts/deploy/terraform-deploy.sh -v 1.0.3-SNAPSHOT

# Preview changes without applying
./scripts/deploy/terraform-deploy.sh --plan-only

# Non-interactive (CI/CD)
./scripts/deploy/terraform-deploy.sh -d --auto-approve
```

This runs `terraform init`, `plan -out=tfplan`, and `apply tfplan`, then saves all outputs (instance IPs, URLs) to `tf-outputs.json`.

### Step 2: Deploy Applications

```bash
# Deploy all components (reads IPs from tf-outputs.json)
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem

# Deploy with explicit version
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem -v 1.0.3-SNAPSHOT

# Deploy only a specific component
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem --component exchange-simulator

# Skip monitoring setup
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem --skip monitoring
```

Components are deployed in dependency order:
1. **Aeron Remote Store** -> `aeron-persistence-private-ip` (no dependencies)
2. **Exchange Simulator** -> `fix-acceptor-private-ip` (conf references Aeron Store IP)
3. **FIX Initiator** -> `ouch-acceptor-private-ip` (conf references Exchange Simulator IP)
4. **OmniView** -> `monitoring-public-ip` (conf references Simulator + Initiator IPs)
5. **Monitoring Stack** -> `monitoring-public-ip` (Prometheus targets use all IPs)

For each component the script: downloads the dist archive from S3 on the target host, extracts it, writes production config files with the correct IP addresses, and creates a systemd service.

### Step 3: Start Services

```bash
# Start all services
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem start

# Check status of all services
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem status

# Restart a single component
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem restart exchange-simulator

# Stop everything (reverse dependency order)
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem stop
```

### Complete Workflow Example

```bash
# 1. Build
mvn install -DskipTests

# 2. Upload to S3
./scripts/deploy/upload-artifacts.sh

# 3. Provision infrastructure
./scripts/deploy/terraform-deploy.sh

# 4. Deploy applications
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem

# 5. Start services
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem start

# 6. Verify
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem status
```

---

## Manual Deployment

The sections below describe manual deployment for each component. Use these as a reference if you need to customize beyond what the automated scripts provide.

---

## Deploy: Exchange Simulator

The exchange simulator is the primary multi-protocol trading engine.

### Option A: SSH-Based Deployment (Recommended for First Setup)

```bash
./scripts/deploy/deploy-exchange-simulator.sh \
  -i omnibridge-key.pem \
  -u ubuntu \
  -h <simulator-private-ip> \
  -p 8080
```

### Option B: Manual Deployment

SSH into the instance (via monitoring jump host):

```bash
ssh -i omnibridge-key.pem -J ubuntu@<monitoring-public-ip> ubuntu@<simulator-private-ip>
```

Install Java and deploy:

```bash
# Install Java 17
sudo apt update && sudo apt install -y openjdk-17-jre-headless

# Create deployment directory
sudo mkdir -p /opt/exchange-simulator
sudo chown ubuntu:ubuntu /opt/exchange-simulator

# Download and extract
aws s3 cp s3://omnibridge-artifacts/omnibridge/production/exchange-simulator-*-dist.tar.gz /tmp/
cd /tmp && tar xzf exchange-simulator-*-dist.tar.gz
cp -r exchange-simulator-*/* /opt/exchange-simulator/
chmod +x /opt/exchange-simulator/bin/*.sh
```

### Configure Aeron Persistence for the Simulator

Create the Aeron persistence configuration overlay. Edit `/opt/exchange-simulator/conf/exchange-simulator-aeron.conf`:

```hocon
# Include base simulator configuration
include "exchange-simulator.conf"

# Override persistence to use Aeron remote replication
persistence {
    enabled = true
    store-type = "aeron"
    base-path = "/opt/exchange-simulator/data/local-cache"
    max-file-size = 256MB
    sync-on-write = false

    aeron {
        media-driver {
            embedded = true
            aeron-dir = "/dev/shm/aeron-simulator"
        }

        publisher-id = 1

        subscribers = [
            {
                name = "primary-remote-store"
                host = "<aeron-remote-store-private-ip>"
                data-port = 40456
                control-port = 40457
            }
        ]

        local-endpoint {
            host = "0.0.0.0"
            replay-port = 40458
        }

        replay {
            timeout-ms = 30000
            max-batch-size = 10000
        }

        heartbeat-interval-ms = 1000
        idle-strategy = "sleeping"
    }
}
```

### Configure the FIX Initiator Session

The exchange simulator is pre-configured with 5 FIX sessions. To connect the initiator deployed later, ensure the CompIDs match. The default session `FIX-SIM-1` expects:

- Simulator (acceptor): SenderCompID=`EXCH1`, TargetCompID=`CLIENT1`
- Initiator (client): SenderCompID=`CLIENT1`, TargetCompID=`EXCH1`

### Apply Kernel Tuning

```bash
# TCP buffer sizes for low-latency trading
sudo sysctl -w net.core.rmem_max=16777216
sudo sysctl -w net.core.wmem_max=16777216
sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"
sudo sysctl -w net.ipv4.tcp_nodelay=1
sudo sysctl -w net.ipv4.tcp_keepalive_time=60

# Make persistent
cat << 'EOF' | sudo tee -a /etc/sysctl.d/99-omnibridge.conf
net.core.rmem_max=16777216
net.core.wmem_max=16777216
net.ipv4.tcp_rmem=4096 87380 16777216
net.ipv4.tcp_wmem=4096 65536 16777216
net.ipv4.tcp_nodelay=1
net.ipv4.tcp_keepalive_time=60
EOF
```

### Create systemd Service

```bash
cat << 'EOF' | sudo tee /etc/systemd/system/exchange-simulator.service
[Unit]
Description=OmniBridge Exchange Simulator
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/exchange-simulator
ExecStart=/usr/bin/java \
    -Xms2g -Xmx2g \
    -XX:+UseZGC \
    -XX:+AlwaysPreTouch \
    -Dconfig.file=/opt/exchange-simulator/conf/exchange-simulator-aeron.conf \
    -Dlogback.configurationFile=/opt/exchange-simulator/conf/logback.xml \
    -Dadmin.port=8080 \
    -cp /opt/exchange-simulator/conf:/opt/exchange-simulator/lib/exchange-simulator.jar \
    com.omnibridge.simulator.ExchangeSimulator
Restart=on-failure
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable exchange-simulator
sudo systemctl start exchange-simulator
```

### Verify

```bash
# Check service status
sudo systemctl status exchange-simulator

# Check admin API
curl http://localhost:8080/api/health

# Check metrics endpoint (scraped by Prometheus)
curl http://localhost:8080/api/metrics | head -20

# Check logs
sudo journalctl -u exchange-simulator -f
```

---

## Deploy: FIX Initiator

The FIX initiator runs as a trading client that connects to the exchange simulator.

### Deploy to EC2

SSH into the initiator instance:

```bash
ssh -i omnibridge-key.pem -J ubuntu@<monitoring-public-ip> ubuntu@<initiator-private-ip>
```

Install Java and extract:

```bash
sudo apt update && sudo apt install -y openjdk-17-jre-headless

sudo mkdir -p /opt/fix-initiator
sudo chown ubuntu:ubuntu /opt/fix-initiator

aws s3 cp s3://omnibridge-artifacts/omnibridge/production/fix-samples-*-dist.tar.gz /tmp/
cd /tmp && tar xzf fix-samples-*-dist.tar.gz
cp -r fix-samples-*/* /opt/fix-initiator/
chmod +x /opt/fix-initiator/bin/*.sh
```

### Create Initiator Configuration

Create `/opt/fix-initiator/conf/initiator-aws.conf`:

```hocon
include "reference.conf"
include "components.conf"

components {
    ouch-engine.enabled = false
}

network {
    name = "initiator-event-loop"
}

admin {
    port = 8082
}

persistence {
    base-path = "/opt/fix-initiator/data/fix-logs"
}

fix-engine {
    sessions = [
        {
            session-id = "INITIATOR_SESSION"
            port = 9876
            host = "<simulator-private-ip>"
            sender-comp-id = "CLIENT1"
            target-comp-id = "EXCH1"
            initiator = true
            begin-string = "FIX.4.4"
            heartbeat-interval = 30
            max-reconnect-attempts = 10
        }
    ]
}
```

Key points:
- `host` must point to the exchange simulator's private IP
- `sender-comp-id` and `target-comp-id` must match one of the simulator's FIX sessions (CLIENT1/EXCH1 for session FIX-SIM-1)
- `initiator = true` since this is the client side

### Create systemd Service

```bash
cat << 'EOF' | sudo tee /etc/systemd/system/fix-initiator.service
[Unit]
Description=OmniBridge FIX Initiator
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/fix-initiator
ExecStart=/usr/bin/java \
    -Xms512m -Xmx1g \
    -XX:+UseZGC \
    -Dconfig.file=/opt/fix-initiator/conf/initiator-aws.conf \
    -Dlogback.configurationFile=/opt/fix-initiator/conf/logback.xml \
    -cp /opt/fix-initiator/conf:/opt/fix-initiator/lib/fix-samples.jar \
    com.omnibridge.apps.fix.initiator.SampleInitiator --auto --count 0
Restart=on-failure
RestartSec=10
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable fix-initiator
sudo systemctl start fix-initiator
```

The `--auto --count 0` flags start the initiator in auto mode that sends orders continuously. Adjust as needed:

| Flag | Purpose |
|------|---------|
| `--auto` | Auto-send sample orders (no interactive prompt) |
| `--count N` | Number of orders to send (0 = continuous) |
| `--latency` | Enable latency tracking mode |
| `--warmup-orders N` | JIT warmup orders (default: 10000) |
| `--test-orders N` | Orders per test run (default: 1000) |
| `--rate N` | Orders per second (default: 100) |

### Verify

```bash
sudo systemctl status fix-initiator
curl http://localhost:8082/api/health
sudo journalctl -u fix-initiator -f
```

Verify the initiator connected to the simulator by checking the simulator's admin API:

```bash
curl http://<simulator-private-ip>:8080/api/sessions | python3 -m json.tool
```

You should see session `FIX-SIM-1` in state `ACTIVE` with the initiator connected.

---

## Deploy: Aeron Remote Persistence Store

The Aeron remote store runs as a standalone service that receives log entries from the exchange simulator via Aeron UDP and persists them in a local Chronicle Queue for disaster recovery.

### Deploy to EC2

SSH into the persistence instance:

```bash
ssh -i omnibridge-key.pem -J ubuntu@<monitoring-public-ip> ubuntu@<aeron-store-private-ip>
```

Install Java, download and extract the distribution:

```bash
sudo apt update && sudo apt install -y openjdk-17-jre-headless

sudo mkdir -p /opt/aeron-store
sudo chown ubuntu:ubuntu /opt/aeron-store

# Download distribution from S3
aws s3 cp s3://omnibridge-artifacts/omnibridge/production/aeron-remote-store-1.0.2-SNAPSHOT-dist.tar.gz /tmp/

# Extract to deployment directory
tar -xzf /tmp/aeron-remote-store-*-dist.tar.gz -C /opt/aeron-store --strip-components=1
chmod +x /opt/aeron-store/bin/*.sh
rm -f /tmp/aeron-remote-store-*-dist.tar.gz
```

The extracted layout:

```
/opt/aeron-store/
├── bin/
│   ├── aeron-remote-store.sh    # Management script (start/stop/status/restart)
│   └── aeron-remote-store.bat
├── conf/
│   ├── aeron-remote-store.conf  # HOCON configuration
│   └── logback.xml              # Logging configuration
├── lib/
│   └── aeron-remote-store.jar   # Fat JAR with all dependencies
├── data/                        # Chronicle Queue storage
└── logs/                        # Application logs
```

### Configure Remote Store

Edit `/opt/aeron-store/conf/aeron-remote-store.conf`:

```hocon
aeron-remote-store {
    base-path = "/opt/aeron-store/data/remote-store"

    aeron {
        media-driver {
            embedded = true
            aeron-dir = "/dev/shm/aeron-remote-store"
        }

        listen {
            host = "0.0.0.0"
            data-port = 40456
            control-port = 40457
        }

        engines = [
            {
                name = "exchange-simulator"
                host = "<simulator-private-ip>"
                replay-port = 40458
            }
        ]

        idle-strategy = "sleeping"
        fragment-limit = 256
    }
}

# Local Chronicle persistence settings
persistence {
    enabled = true
    store-type = "chronicle"
    base-path = "/opt/aeron-store/data/remote-store"
    max-file-size = 256MB
    sync-on-write = false
}
```

Key points:
- `listen.host = "0.0.0.0"` to accept data from any source in the VPC
- `engines[0].host` must point to the exchange simulator's private IP for replay responses
- The data port (40456) and control port (40457) must match the simulator's subscriber config
- The replay port (40458) must match the simulator's `local-endpoint.replay-port`

### Create systemd Service

```bash
cat << 'EOF' | sudo tee /etc/systemd/system/aeron-remote-store.service
[Unit]
Description=OmniBridge Aeron Remote Persistence Store
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/aeron-store
ExecStart=/usr/bin/java \
    -Xms1g -Xmx2g \
    -XX:+UseZGC \
    -XX:+AlwaysPreTouch \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
    -Djava.net.preferIPv4Stack=true \
    -cp "/opt/aeron-store/conf:/opt/aeron-store/lib/aeron-remote-store.jar" \
    com.omnibridge.persistence.aeron.AeronRemoteStoreMain \
    -c /opt/aeron-store/conf/aeron-remote-store.conf
Restart=on-failure
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable aeron-remote-store
sudo systemctl start aeron-remote-store
```

### Verify

```bash
sudo systemctl status aeron-remote-store
sudo journalctl -u aeron-remote-store -f

# Check Chronicle data directory is being populated
ls -la /opt/aeron-store/data/remote-store/

# Check Aeron shared memory
ls -la /dev/shm/aeron-remote-store/
```

---

## Deploy: Monitoring Infrastructure

The monitoring stack includes Prometheus, Grafana, Alertmanager, and OmniView.

### Option A: Terraform (Full Infrastructure)

Use the existing Terraform modules as described in [AWS_MONITORING_SETUP.md](AWS_MONITORING_SETUP.md). The Prometheus configuration needs to be updated to scrape all instances.

### Option B: Manual Docker Compose on EC2

SSH into the monitoring instance:

```bash
ssh -i omnibridge-key.pem ubuntu@<monitoring-public-ip>
```

Install Docker:

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker ubuntu
newgrp docker
```

Create monitoring directory:

```bash
sudo mkdir -p /opt/monitoring/{prometheus,grafana,alertmanager}
sudo chown -R ubuntu:ubuntu /opt/monitoring
```

### Configure Prometheus

Create `/opt/monitoring/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "rules/*.yml"

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

scrape_configs:
  # Exchange Simulator
  - job_name: 'exchange-simulator'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['<simulator-private-ip>:8080']
        labels:
          app: 'exchange-simulator'
          environment: 'production'

  # FIX Initiator
  - job_name: 'fix-initiator'
    metrics_path: '/api/metrics'
    static_configs:
      - targets: ['<initiator-private-ip>:8082']
        labels:
          app: 'fix-initiator'
          environment: 'production'

  # Prometheus self-monitoring
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
```

### Create Alert Rules

Create `/opt/monitoring/prometheus/rules/omnibridge-alerts.yml`:

```yaml
groups:
  - name: omnibridge
    rules:
      - alert: TradingInstanceDown
        expr: up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Trading instance {{ $labels.instance }} is down"

      - alert: SessionDisconnected
        expr: omnibridge_session_connected == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Session {{ $labels.session }} is disconnected"

      - alert: HighFixLatency
        expr: omnibridge_message_latency_seconds{quantile="0.99"} > 0.001
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "FIX p99 latency above 1ms on {{ $labels.instance }}"

      - alert: SequenceNumberGap
        expr: omnibridge_sequence_gap_total > 10
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Sequence gap detected on {{ $labels.session }}"

      - alert: HighHeapUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Heap usage above 85% on {{ $labels.instance }}"
```

### Docker Compose

Create `/opt/monitoring/docker-compose.yml`:

```yaml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - ./prometheus/rules:/etc/prometheus/rules
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=30d'
      - '--web.enable-lifecycle'
    restart: unless-stopped

  grafana:
    image: grafana/grafana:10.2.2
    container_name: grafana
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:-admin}
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana-data:/var/lib/grafana
    restart: unless-stopped

  alertmanager:
    image: prom/alertmanager:v0.26.0
    container_name: alertmanager
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:
```

### Deploy OmniView

```bash
aws s3 cp s3://omnibridge-artifacts/omnibridge/production/omniview-*-dist.tar.gz /tmp/
cd /tmp && tar xzf omniview-*-dist.tar.gz

sudo mkdir -p /opt/omniview
sudo chown ubuntu:ubuntu /opt/omniview
cp -r omniview-*/* /opt/omniview/
chmod +x /opt/omniview/bin/*.sh
```

Configure OmniView to connect to the exchange simulator and initiator. Edit `/opt/omniview/conf/omniview.conf`:

```hocon
omniview {
    port = 3000
    apps = [
        {
            name = "Exchange Simulator"
            host = "<simulator-private-ip>"
            port = 8080
            protocol = "http"
        },
        {
            name = "FIX Initiator"
            host = "<initiator-private-ip>"
            port = 8082
            protocol = "http"
        }
    ]
}
```

Start OmniView:

```bash
/opt/omniview/bin/omniview.sh start 3000
```

### Start the Monitoring Stack

```bash
cd /opt/monitoring
GRAFANA_PASSWORD=<your-grafana-password> docker compose up -d
```

### Create systemd Service for Monitoring

```bash
cat << 'EOF' | sudo tee /etc/systemd/system/omnibridge-monitoring.service
[Unit]
Description=OmniBridge Monitoring Stack
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/monitoring
ExecStart=/usr/bin/docker compose up -d
ExecStop=/usr/bin/docker compose down

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable omnibridge-monitoring
```

### Verify

```bash
# Check all containers running
docker compose ps

# Check Prometheus targets
curl http://localhost:9090/api/v1/targets | python3 -m json.tool

# Access Grafana
# Open http://<monitoring-public-ip>:3001 (admin/<your-password>)

# Access OmniView
# Open http://<monitoring-public-ip>:3000
```

---

## Deployment Order

The services must be started in this order due to network dependencies:

```
1. Aeron Remote Store        (no dependencies, receives data)
2. Exchange Simulator         (depends on Aeron Remote Store IP)
3. FIX Initiator              (depends on Exchange Simulator IP)
4. Monitoring Stack           (depends on all admin API IPs)
5. OmniView                   (depends on all admin API IPs)
```

### Quick Start Checklist

```
[ ] AWS credentials configured
[ ] EC2 key pair created
[ ] S3 artifacts uploaded (mvn install -DskipTests + s3 cp)
[ ] Terraform applied (or EC2 instances manually provisioned)
[ ] Aeron Remote Store deployed and running on persistence instance
[ ] Exchange Simulator deployed with Aeron config pointing to remote store
[ ] FIX Initiator deployed with host pointing to simulator
[ ] Prometheus configured with all scrape targets
[ ] Grafana accessible and dashboards provisioned
[ ] OmniView connected to simulator and initiator
[ ] Session FIX-SIM-1 shows ACTIVE in both OmniView and admin API
```

---

## Disaster Recovery with Aeron

### Normal Operation

During normal operation, the exchange simulator writes every log entry to:
1. **Local Chronicle Queue** (synchronous, sub-microsecond) -- used for all reads
2. **Aeron UDP** (fire-and-forget) -- replicated to remote store

The remote store receives SBE-encoded `LogEntryMessage` frames and writes them to its own Chronicle Queue.

### Recovery Scenario

If the exchange simulator loses its local data (EBS failure, instance replacement):

1. Start the simulator -- local Chronicle cache is empty
2. Call the recovery API or trigger programmatically:

```bash
# SSH into the simulator instance
# Recovery can be triggered via the AeronLogStore.recoverFromRemote() API
# This sends a ReplayRequest to the remote store, which streams all entries back
```

The ReplayRequest protocol supports rich filtering:
- **Direction**: BOTH, INBOUND, or OUTBOUND only
- **Sequence range**: fromSequenceNumber / toSequenceNumber
- **Time range**: fromTimestamp / toTimestamp
- **Max entries**: limit the number of entries returned
- **Stream name**: replay a specific session or all sessions

### Monitoring Replication Health

Check that the Aeron remote store is receiving data:

```bash
# On the remote store instance
ls -la /opt/aeron-store/data/remote-store/

# Check Chronicle Queue files are growing
watch -n 5 'du -sh /opt/aeron-store/data/remote-store/*'

# Check logs for entry reception
sudo journalctl -u aeron-remote-store --since "5 minutes ago" | grep "entries"
```

---

## Cost Estimate

### Full Stack (monthly, us-east-1, on-demand)

| Resource | Spec | Est. Cost |
|----------|------|-----------|
| EC2 Exchange Simulator | c5.xlarge (4 vCPU, 8GB) | ~$124 |
| EC2 FIX Initiator | t3.medium (2 vCPU, 4GB) | ~$30 |
| EC2 Aeron Remote Store | t3.large (2 vCPU, 8GB) | ~$60 |
| EC2 Monitoring | t3.large (2 vCPU, 8GB) | ~$60 |
| EBS Simulator | 100GB GP3, 6000 IOPS | ~$11 |
| EBS Initiator | 50GB GP3 | ~$4 |
| EBS Remote Store | 200GB GP3, 6000 IOPS | ~$21 |
| EBS Monitoring | 100GB GP3 | ~$8 |
| NAT Gateway | Data processing | ~$35 |
| Elastic IP | 1 EIP | ~$4 |
| S3 (artifacts + state) | < 1GB | ~$1 |
| **Total** | | **~$358/mo** |

Use Reserved Instances or Savings Plans for 40-60% savings on long-running production workloads.

---

## Ongoing Management

### SSH Access

```bash
# Monitoring instance (public IP, jump host)
ssh -i omnibridge-key.pem ubuntu@<monitoring-public-ip>

# Other instances (via monitoring as jump host)
ssh -i omnibridge-key.pem -J ubuntu@<monitoring-public-ip> ubuntu@<target-private-ip>
```

### Service Management

```bash
# Exchange Simulator
sudo systemctl {status|start|stop|restart} exchange-simulator
sudo journalctl -u exchange-simulator -f

# FIX Initiator
sudo systemctl {status|start|stop|restart} fix-initiator
sudo journalctl -u fix-initiator -f

# Aeron Remote Store
sudo systemctl {status|start|stop|restart} aeron-remote-store
sudo journalctl -u aeron-remote-store -f

# Monitoring Stack
cd /opt/monitoring && docker compose {ps|logs|restart}
sudo systemctl {status|restart} omnibridge-monitoring

# OmniView
/opt/omniview/bin/omniview.sh {status|start|stop|restart}
```

### Updating Applications

```bash
# 1. Build new version locally
mvn install -DskipTests

# 2. Upload to S3
./scripts/deploy/upload-artifacts.sh

# 3. Re-deploy all applications (stop, deploy, start)
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem stop
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem start

# Or update a single component
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem stop exchange-simulator
./scripts/deploy/setup-remote.sh -f tf-outputs.json -i omnibridge-key.pem --component exchange-simulator
./scripts/deploy/manage-services.sh -f tf-outputs.json -i omnibridge-key.pem start exchange-simulator
```

### Log Locations

| Instance | Log Path |
|----------|----------|
| Exchange Simulator | `/opt/exchange-simulator/logs/` + `journalctl -u exchange-simulator` |
| FIX Initiator | `/opt/fix-initiator/data/fix-logs/` + `journalctl -u fix-initiator` |
| Aeron Remote Store | `/opt/aeron-store/logs/` + `journalctl -u aeron-remote-store` |
| Monitoring | `/opt/monitoring/` + `docker compose logs` |
| OmniView | `/opt/omniview/logs/` |

---

## Tearing Down

### Terraform-Managed Resources

```bash
cd infrastructure/terraform/environments/production
terraform destroy
```

### Manually-Provisioned Resources

Terminate EC2 instances and delete EBS volumes via the AWS Console or CLI:

```bash
# List running instances
aws ec2 describe-instances --filters "Name=tag:Project,Values=omnibridge" \
    --query 'Reservations[].Instances[].[InstanceId,State.Name,Tags[?Key==`Name`].Value|[0]]' \
    --output table

# Terminate (replace with actual instance IDs)
aws ec2 terminate-instances --instance-ids i-xxx i-yyy i-zzz
```

**Warning:** EBS volumes are destroyed with instances. Back up Chronicle Queue data from `/opt/aeron-store/data/` before destroying the persistence instance.
