# OmniBridge AWS Deployment Diagram

## Network Topology

```
                            INTERNET
                               |
                    +----------+----------+
                    |   Internet Gateway   |
                    +----------+----------+
                               |
            VPC: vpc-09e53c49ca0d17999 (10.0.0.0/16)
  ============================================================================

  PUBLIC SUBNET (10.0.10.0/24)            NAT Gateway (54.80.69.232)
  +--------------------------------------+       |
  | MONITORING / BASTION HOST            |       | (outbound-only for
  | 35.171.71.179 (public)               |       |  private subnets)
  | i-0bf3bcc93eaf262f5                  |       |
  |                                      +-------+
  |  +--- OmniView (systemd) ----------+|
  |  | Java, port 3000                  ||
  |  | Proxies to engine admin APIs     ||
  |  +----------------------------------+|
  |                                      |
  |  +--- Docker Compose Stack ---------+|
  |  | Prometheus    :9090               ||
  |  | Grafana       :3001 (public)      ||
  |  | Alertmanager  :9093               ||
  |  +----------------------------------+|
  +--------------------------------------+
       |              |             |
       | SSH bastion  | HTTP scrape | HTTP scrape
       | (jump host)  | /api/metrics| /api/metrics
       |              |             |
  =====|==============|=============|==========================================
       |              |             |
  PRIVATE SUBNETS (no public IPs, outbound via NAT)
       |              |             |
  -----+--------------+-------------+------------------------------------------
       |              |             |
  TRADING SUBNET A (10.0.1.0/24)   |
  +--------------------------------+---+
  | FIX HOST                           |
  | 10.0.1.186                         |
  | i-060c5f93b5ced6bb5                |
  |                                    |
  |  +--- Exchange Simulator ---------+|
  |  | (systemd)                       ||
  |  | FIX acceptor    :9876           ||
  |  | OUCH acceptor   :9200/:9201    ||
  |  | iLink3 acceptor :9300           ||
  |  | Optiq acceptor  :9400           ||
  |  | Pillar acceptor :9500           ||
  |  | Admin API       :8080           ||
  |  | Metrics         :8080/api/metrics|
  |  +---------------------------------+|
  +------------------------------------+
       |
       | Aeron UDP publish
       | (ports 40456-40457)
       |
  -----+----------------------------------------------------------------
       |
  TRADING SUBNET B (10.0.2.0/24)
  +------------------------------------+
  | OUCH HOST                          |
  | 10.0.2.148                         |
  | i-080bb93cdd3d6822d                |
  |                                    |
  |  +--- FIX Initiator (systemd) ----+|
  |  | Connects to Exchange Sim :9876  ||
  |  | Admin API       :8082           ||
  |  | Metrics         :8082/api/metrics|
  |  | Demo mode: auto-sends orders    ||
  |  +---------------------------------+|
  +------------------------------------+
       |
       | FIX 4.4 (TCP :9876)
       | (to Exchange Simulator)
       |
  =====================================================================
       |
  PERSISTENCE SUBNET (10.0.3.0/24)
  +------------------------------------+
  | AERON HOST                         |
  | 10.0.3.231                         |
  | i-0fef889a5b15946b2                |
  |                                    |
  |  +--- Aeron Remote Store ---------+|
  |  | (systemd)                       ||
  |  | UDP listen :40456-40457         ||
  |  | Admin API  :8083                ||
  |  | Chronicle Queue data:           ||
  |  |   /opt/aeron-store/data/        ||
  |  |   remote-store/                 ||
  |  +---------------------------------+|
  |                                    |
  |  +--- MCP Server (systemd) -------+|
  |  | HTTP/SSE   :8090                ||
  |  | Reads Chronicle Queue from:     ||
  |  |   /opt/aeron-store/data/        ||
  |  |   remote-store/                 ||
  |  | RocksDB index:                  ||
  |  |   /opt/mcp-server/data/         ||
  |  |   fix-index/                    ||
  |  +---------------------------------+|
  +------------------------------------+
```

## Data Flow

```
  +----------------+    FIX 4.4 (TCP)    +---------------------+
  | FIX Initiator  | ------------------> | Exchange Simulator   |
  | (10.0.2.148)   | <------------------ | (10.0.1.186)         |
  |                |   ExecutionReports   |                     |
  | Sends:         |                     | Receives:            |
  |  NewOrderSingle|                     |  Orders              |
  |  OrderCancel   |                     | Sends:               |
  |  OrderModify   |                     |  Fills/Acks/Rejects  |
  +----------------+                     +----------+-----------+
                                                    |
                                         Aeron UDP publish
                                         (FIX messages persisted)
                                                    |
                                                    v
                                         +----------+-----------+
                                         | Aeron Remote Store   |
                                         | (10.0.3.231:40456)   |
                                         |                      |
                                         | Chronicle Queue      |
                                         | /opt/aeron-store/    |
                                         | data/remote-store/   |
                                         +----------+-----------+
                                                    |
                                         Reads Chronicle Queue
                                         (same filesystem)
                                                    |
                                                    v
                                         +----------+-----------+
                                         | MCP Server           |
                                         | (10.0.3.231:8090)    |
                                         |                      |
                                         | HTTP/SSE API for     |
                                         | FIX message queries  |
                                         | RocksDB secondary    |
                                         | index for fast lookup|
                                         +----------------------+
```

## Monitoring Flow

```
  Prometheus (monitoring host :9090)
       |
       +---> scrape exchange-simulator  10.0.1.186:8080/api/metrics  (every 15s)
       +---> scrape fix-initiator       10.0.2.148:8082/api/metrics  (every 15s)
       +---> scrape aeron-remote-store  10.0.3.231:8083/api/metrics  (every 15s)
       |
       v
  Grafana (:3001) <--- reads from Prometheus
       |
       +---> OmniBridge Overview dashboard (CPU, memory, uptime, sessions)
       +---> FIX Engine dashboard (message rates, latency, session state)
       |
  Alertmanager (:9093) <--- receives alerts from Prometheus rules
       |
       +---> Fires on: service down, high error rate, session disconnects
```

## OmniView Proxy Flow

```
  Browser ---> OmniView (35.171.71.179:3000)
                    |
                    +---> /api/proxy/exch-sim/*    --> 10.0.1.186:8080/api/*
                    +---> /api/proxy/fix-init/*     --> 10.0.2.148:8082/api/*
                    +---> /api/proxy/aeron-store/*  --> 10.0.3.231:8083/api/*
                    |
                    +---> WebSocket /ws (real-time session state updates)
```

## External Access

```
  DIRECTLY ACCESSIBLE (public IP: 35.171.71.179):
    - Grafana        http://35.171.71.179:3001
    - OmniView       http://35.171.71.179:3000

  VIA SSH TUNNEL (through bastion):
    - MCP Server     ssh -i omnibridge-key.pem -L 8090:10.0.3.231:8090 ubuntu@35.171.71.179
    - Exch Sim Admin ssh -i omnibridge-key.pem -L 8080:10.0.1.186:8080 ubuntu@35.171.71.179
    - FIX Initiator  ssh -i omnibridge-key.pem -L 8082:10.0.2.148:8082 ubuntu@35.171.71.179
    - Prometheus     ssh -i omnibridge-key.pem -L 9090:localhost:9090   ubuntu@35.171.71.179

  SSH TO PRIVATE HOSTS:
    ssh -i omnibridge-key.pem \
        -o "ProxyCommand=ssh -i omnibridge-key.pem -W %h:%p ubuntu@35.171.71.179" \
        ubuntu@<PRIVATE_IP>
```

## Service Dependencies (start order)

```
  aeron-store ─────────────> mcp-server
       |                     (reads Chronicle Queue)
       |
       v
  exchange-simulator ──────> fix-initiator
  (publishes to store        (connects to simulator
   via Aeron UDP)             via FIX TCP :9876)
       |                          |
       v                          v
  omniview (proxies to admin APIs of both)
       |
       v
  monitoring (scrapes metrics from all services)
```

## Security Groups

| Security Group | Inbound Rules |
|----------------|---------------|
| **monitoring-sg** | SSH :22 from anywhere, Grafana :3001 from anywhere, OmniView :3000 from anywhere |
| **trading-sg** | FIX :9876 from monitoring-sg, OUCH :9200 from monitoring-sg, Admin :8080-8082 from monitoring-sg, Metrics :8080 from monitoring-sg, Aeron replay UDP :40458 from persistence-sg |
| **persistence-sg** | Aeron UDP :40456-40457 from trading-sg, Admin :8083 from monitoring-sg, Metrics :8080 from monitoring-sg, MCP :8090 from monitoring-sg, SSH :22 from monitoring-sg |
