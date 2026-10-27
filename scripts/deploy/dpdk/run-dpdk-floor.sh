#!/bin/bash
# DPDK network floor on the AWS stack: a UDP ping-pong through the ENA
# poll-mode driver between the two trading hosts, next to sockperf on the
# kernel path. Answers "what does the network cost without the kernel?".
#
# Needs a provisioned stack (tf-outputs.json) and the AWS CLI. Each phase is
# one step so a failure can be retried:
#
#   run-dpdk-floor.sh enis      create a second ENI per trading host in the
#                               trading subnet/SG, attach it, allow UDP 11111
#   run-dpdk-floor.sh setup     install dpdk + sockperf, hugepages, build
#                               pingpong, bind the second ENI to vfio-pci
#   run-dpdk-floor.sh baseline [seconds]      sockperf TCP and UDP, primary ENI
#   run-dpdk-floor.sh dpdk [count] [warmup] [interval-us]
#                               reflector on the FIX host, paced sender on
#                               the OUCH host, both on the isolated cpu 3
#   run-dpdk-floor.sh fstack    build F-Stack and the omni-bridge shim on both hosts,
#                               write /opt/fstack/fstack.ini for the second ENI and
#                               bind it (for run-aws-latency-test.sh --transport fstack)
#   run-dpdk-floor.sh fstack-echo [count] [warmup] [interval-us] [size]
#                               TCP echo over F-Stack without a JVM (fstack-echo.c):
#                               server on the FIX host, paced client on the OUCH host,
#                               next to the raw ping-pong; needs the fstack phase
#   run-dpdk-floor.sh cleanup   detach and delete the ENIs (before destroy)
#
# State (ENI ids, IPs, MACs, subnet CIDR) is kept in logs/dpdk-floor/state.env.
set -euo pipefail
PHASE="${1:-}"; shift || true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TF="$PROJECT_DIR/tf-outputs.json"
PEM="$PROJECT_DIR/omnibridge-key.pem"
OUT="$PROJECT_DIR/logs/dpdk-floor"; mkdir -p "$OUT"
STATE="$OUT/state.env"
CPU=3
val() { jq -r ".$1.value // .$1" "$TF"; }
MON=$(val monitoring_public_ip); FIX=$(val fix_acceptor_private_ip); OUCH=$(val ouch_acceptor_private_ip)
FIX_ID=$(val fix_acceptor_instance_id); OUCH_ID=$(val ouch_acceptor_instance_id); VPC=$(val vpc_id)
SSH_OPTS="-i $PEM -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 -o BatchMode=yes"
PROXY="ssh -i $PEM -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -W %h:%p ubuntu@$MON"
run_on() { local h="$1"; shift; ssh $SSH_OPTS -o "ProxyCommand=$PROXY" "ubuntu@$h" "$@"; }
copy_to() { local h="$1"; shift; scp $SSH_OPTS -o "ProxyCommand=$PROXY" "$@" "ubuntu@$h:~/dpdk/"; }
[ -f "$STATE" ] && source "$STATE"
EAL="-l $CPU -n 2 --file-prefix pp"

case "$PHASE" in
enis)
    SG=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=$VPC Name=tag:Name,Values=*trading-sg --query 'SecurityGroups[0].GroupId' --output text)
    SUBNET=$(aws ec2 describe-instances --instance-ids $FIX_ID --query 'Reservations[0].Instances[0].SubnetId' --output text)
    echo "trading sg $SG subnet $SUBNET"
    aws ec2 authorize-security-group-ingress --group-id $SG --ip-permissions "IpProtocol=udp,FromPort=11111,ToPort=11111,UserIdGroupPairs=[{GroupId=$SG}]" >/dev/null && echo "udp 11111 allowed"
    ENI_FIX=$(aws ec2 create-network-interface --subnet-id $SUBNET --groups $SG --description dpdk-fix --query NetworkInterface.NetworkInterfaceId --output text)
    ENI_OUCH=$(aws ec2 create-network-interface --subnet-id $SUBNET --groups $SG --description dpdk-ouch --query NetworkInterface.NetworkInterfaceId --output text)
    aws ec2 attach-network-interface --network-interface-id $ENI_FIX --instance-id $FIX_ID --device-index 1 >/dev/null
    aws ec2 attach-network-interface --network-interface-id $ENI_OUCH --instance-id $OUCH_ID --device-index 1 >/dev/null
    Q='NetworkInterfaces[0].[PrivateIpAddress,MacAddress]'
    read FIX2 FIX2_MAC < <(aws ec2 describe-network-interfaces --network-interface-ids $ENI_FIX --query "$Q" --output text)
    read OUCH2 OUCH2_MAC < <(aws ec2 describe-network-interfaces --network-interface-ids $ENI_OUCH --query "$Q" --output text)
    CIDR=$(aws ec2 describe-subnets --subnet-ids $SUBNET --query 'Subnets[0].CidrBlock' --output text)
    printf 'SG=%s\nENI_FIX=%s\nENI_OUCH=%s\nFIX2=%s\nFIX2_MAC=%s\nOUCH2=%s\nOUCH2_MAC=%s\nCIDR=%s\n' \
        "$SG" "$ENI_FIX" "$ENI_OUCH" "$FIX2" "$FIX2_MAC" "$OUCH2" "$OUCH2_MAC" "$CIDR" > "$STATE"
    cat "$STATE"
    ;;
fstack)
    # F-Stack on both trading hosts; the engines then run with network.transport = fstack
    if [ -z "${CIDR:-}" ]; then
        SUBNET=$(aws ec2 describe-network-interfaces --network-interface-ids $ENI_FIX --query 'NetworkInterfaces[0].SubnetId' --output text)
        CIDR=$(aws ec2 describe-subnets --subnet-ids $SUBNET --query 'Subnets[0].CidrBlock' --output text)
        echo "CIDR=$CIDR" >> "$STATE"
    fi
    SHIM="$PROJECT_DIR/network-dpdk/src/main/c/omnibridge_fstack.c"
    for h in $FIX $OUCH; do run_on $h "mkdir -p ~/dpdk"; copy_to $h "$SHIM" "$SCRIPT_DIR/setup-fstack-host.sh"; done
    echo "=== FIX host ($FIX2 on $FIX2_MAC, cpu $CPU)"
    run_on $FIX  "bash ~/dpdk/setup-fstack-host.sh $FIX2_MAC $FIX2 $CIDR $CPU" 2>&1 | grep -v debconf
    echo "=== OUCH host ($OUCH2 on $OUCH2_MAC, cpu $CPU)"
    run_on $OUCH "bash ~/dpdk/setup-fstack-host.sh $OUCH2_MAC $OUCH2 $CIDR $CPU" 2>&1 | grep -v debconf
    ;;
setup)
    for h in $FIX $OUCH; do run_on $h "mkdir -p ~/dpdk"; copy_to $h "$SCRIPT_DIR/pingpong.c" "$SCRIPT_DIR/setup-dpdk-host.sh"; done
    echo "=== FIX host";  run_on $FIX  "bash ~/dpdk/setup-dpdk-host.sh $FIX2_MAC"  2>&1 | grep -v debconf
    echo "=== OUCH host"; run_on $OUCH "bash ~/dpdk/setup-dpdk-host.sh $OUCH2_MAC" 2>&1 | grep -v debconf
    ;;
baseline)
    T=${1:-30}
    run_on $FIX "pkill -x sockperf || true; nohup sockperf sr --tcp -i 0.0.0.0 -p 11111 > /tmp/sp-tcp.log 2>&1 < /dev/null & sleep 1; nohup sockperf sr -i 0.0.0.0 -p 11111 > /tmp/sp-udp.log 2>&1 < /dev/null & sleep 1"
    for proto in "--tcp" ""; do
        echo "=== sockperf ${proto:-udp} 200 B, primary ENI, $T s"
        run_on $OUCH "sockperf pp $proto -i $FIX -p 11111 -m 200 -t $T --full-rtt" | tee "$OUT/sockperf${proto:--udp}.log" | grep -E "Summary|percentile|dropped"
    done
    run_on $FIX "pkill -x sockperf || true"
    ;;
dpdk)
    N=${1:-100000}; W=${2:-20000}; IV=${3:-1000}
    # bounded: the Windows ssh client sometimes never returns from a session whose command left a
    # detached process behind, although the command's output has arrived
    timeout 30 ssh $SSH_OPTS -o "ProxyCommand=$PROXY" "ubuntu@$FIX" "sudo pkill -x pingpong || true; cd ~/dpdk && sudo setsid nohup ./pingpong $EAL -- --mode reflect > /tmp/reflect.log 2>&1 < /dev/null & sleep 4; grep -v '^EAL' /tmp/reflect.log" < /dev/null || true
    echo "=== DPDK ping-pong: $W warmup + $N at every $IV us, $OUCH2 -> $FIX2"
    run_on $OUCH "cd ~/dpdk && sudo timeout 600 ./pingpong $EAL -- --mode send --src-ip $OUCH2 --dst-ip $FIX2 --dst-mac $FIX2_MAC -n $N -w $W --interval-us $IV --timeout-ms 100 2>&1 | grep -v '^EAL'" | tee "$OUT/dpdk-$(date +%Y%m%d-%H%M%S).log"
    run_on $FIX "sudo pkill -INT -x pingpong || true; sleep 1; tail -1 /tmp/reflect.log"
    ;;
fstack-echo)
    N=${1:-100000}; W=${2:-20000}; IV=${3:-1000}; SZ=${4:-200}
    BUILD='cd ~/dpdk && export PKG_CONFIG_PATH=$(cat /opt/fstack/FF_DPDK)/lib/x86_64-linux-gnu/pkgconfig:$(cat /opt/fstack/FF_DPDK)/lib/pkgconfig && gcc -O2 -o fstack-echo fstack-echo.c $(pkg-config --cflags libdpdk) -I/opt/fstack -I/opt/fstack-src/lib -Wl,--whole-archive /opt/fstack/libfstack.a -Wl,--no-whole-archive $(pkg-config --static --libs libdpdk) -lrt -lm -ldl -lcrypto -lpthread -lnuma && echo "fstack-echo built on $(hostname)"'
    for h in $FIX $OUCH; do copy_to $h "$SCRIPT_DIR/fstack-echo.c"; run_on $h "$BUILD" 2>&1 | grep -v "warning:" ; done
    # the port is TCP 11111 (the sockperf rule between the trading hosts); F-Stack owns the second ENI
    timeout 30 ssh $SSH_OPTS -o "ProxyCommand=$PROXY" "ubuntu@$FIX" "sudo pkill -x fstack-echo || true; cd ~/dpdk && sudo setsid nohup ./fstack-echo --mode server --port 11111 > /tmp/fstack-echo-server.log 2>&1 < /dev/null & sleep 6; grep -v '^EAL\|^\[' /tmp/fstack-echo-server.log | tail -3" < /dev/null || true
    echo "=== F-Stack TCP echo: $W warmup + $N x $SZ B every $IV us, $OUCH2 -> $FIX2:11111"
    # stdin from /dev/null and a KILL timeout: the client run from a background shell with an
    # open stdin hung its ssh session for good without ever connecting
    run_on $OUCH "cd ~/dpdk && sudo timeout -s KILL 900 ./fstack-echo --mode client --ip $FIX2 --port 11111 -n $N -w $W --interval-us $IV --size $SZ < /dev/null 2>&1 | grep -v '^EAL\|^\['" < /dev/null | tee "$OUT/fstack-echo-$(date +%Y%m%d-%H%M%S).log"
    run_on $FIX "sudo pkill -x fstack-echo || true; sleep 1; tail -2 /tmp/fstack-echo-server.log"
    ;;
cleanup)
    for eni in $ENI_FIX $ENI_OUCH; do
        att=$(aws ec2 describe-network-interfaces --network-interface-ids $eni --query 'NetworkInterfaces[0].Attachment.AttachmentId' --output text)
        [ "$att" != "None" ] && aws ec2 detach-network-interface --attachment-id $att --force
    done
    # a detach can take a minute or more; terraform destroy fails while an ENI is still attached
    for eni in $ENI_FIX $ENI_OUCH; do
        for i in $(seq 1 30); do
            st=$(aws ec2 describe-network-interfaces --network-interface-ids $eni --query 'NetworkInterfaces[0].Status' --output text)
            [ "$st" = "available" ] && break
            sleep 10
        done
        aws ec2 delete-network-interface --network-interface-id $eni && echo "deleted $eni"
    done
    ;;
*) echo "usage: $0 enis|setup|baseline|dpdk|fstack|fstack-echo|cleanup"; exit 1 ;;
esac
