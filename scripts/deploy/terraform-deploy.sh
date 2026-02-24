#!/bin/bash
# =============================================================================
# Terraform Deploy Script
# =============================================================================
# Provisions (or re-provisions) the OmniBridge production infrastructure and
# saves Terraform outputs to a JSON file for use by setup-remote.sh.
#
# Usage: ./terraform-deploy.sh [options]
#
# Options:
#   -d, --destroy     Destroy existing infrastructure before applying
#   -o, --output      Output file for Terraform outputs (default: tf-outputs.json)
#   -e, --env         Terraform environment directory (default: production)
#   -v, --version     Override app_version in terraform.tfvars
#   --plan-only       Run init + plan only, do not apply
#   --auto-approve    Skip confirmation prompts
#   --skip-ip-update  Skip auto-detection of public IP for ssh_cidrs/grafana_cidrs
#
# Examples:
#   ./terraform-deploy.sh                          # Init + plan + apply
#   ./terraform-deploy.sh -d                       # Destroy + init + plan + apply
#   ./terraform-deploy.sh -d --auto-approve        # Non-interactive full redeploy
#   ./terraform-deploy.sh --plan-only              # Preview changes only
#   ./terraform-deploy.sh -v 1.0.3-SNAPSHOT        # Deploy with version override
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TF_BASE="$SCRIPT_DIR/infrastructure/terraform/environments"

# Defaults
DESTROY=false
OUTPUT_FILE="$SCRIPT_DIR/tf-outputs.json"
ENVIRONMENT="production"
PLAN_ONLY=false
AUTO_APPROVE=false
VERSION_OVERRIDE=""
SKIP_IP_UPDATE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--destroy)
            DESTROY=true
            shift
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -e|--env)
            ENVIRONMENT="$2"
            shift 2
            ;;
        -v|--version)
            VERSION_OVERRIDE="$2"
            shift 2
            ;;
        --plan-only)
            PLAN_ONLY=true
            shift
            ;;
        --auto-approve)
            AUTO_APPROVE=true
            shift
            ;;
        --skip-ip-update)
            SKIP_IP_UPDATE=true
            shift
            ;;
        --help)
            echo "Terraform Deploy Script"
            echo ""
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  -d, --destroy     Destroy existing infrastructure before applying"
            echo "  -o, --output      Output file for Terraform outputs (default: tf-outputs.json)"
            echo "  -e, --env         Terraform environment (default: production)"
            echo "  -v, --version     Override app_version in terraform.tfvars"
            echo "  --plan-only       Run init + plan only, do not apply"
            echo "  --auto-approve    Skip confirmation prompts"
            echo "  --skip-ip-update  Skip auto-detection of public IP for ssh_cidrs/grafana_cidrs"
            exit 0
            ;;
        *)
            echo "Error: Unknown option: $1"
            exit 1
            ;;
    esac
done

TF_DIR="$TF_BASE/$ENVIRONMENT"

if [ ! -d "$TF_DIR" ]; then
    echo "Error: Terraform environment directory not found: $TF_DIR"
    exit 1
fi

# Build version override flags
TF_VAR_FLAGS=""
if [ -n "$VERSION_OVERRIDE" ]; then
    TF_VAR_FLAGS="-var=app_version=$VERSION_OVERRIDE"
fi

# -------------------------------------------------------------------------
# Public IP lookup (used to update ssh_cidrs / grafana_cidrs)
# -------------------------------------------------------------------------
lookup_public_ip() {
    local ip=""
    for svc in "https://checkip.amazonaws.com" "https://api.ipify.org" "https://icanhazip.com"; do
        ip=$(curl -s --max-time 5 "$svc" 2>/dev/null | tr -d '[:space:]')
        if [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$ip"
            return 0
        fi
    done
    return 1
}

DETECTED_IP=""
if [ "$SKIP_IP_UPDATE" = false ]; then
    DETECTED_IP=$(lookup_public_ip) || true
fi

APPROVE_FLAG=""
if [ "$AUTO_APPROVE" = true ]; then
    APPROVE_FLAG="-auto-approve"
fi

echo "========================================================"
echo "Terraform Deploy - OmniBridge $ENVIRONMENT"
echo "========================================================"
echo "Environment:  $ENVIRONMENT"
echo "Directory:    $TF_DIR"
echo "Output File:  $OUTPUT_FILE"
echo "Destroy:      $DESTROY"
echo "Plan Only:    $PLAN_ONLY"
if [ -n "$DETECTED_IP" ]; then
    echo "Public IP:    $DETECTED_IP (auto-detected)"
elif [ "$SKIP_IP_UPDATE" = true ]; then
    echo "Public IP:    (skipped)"
else
    echo "Public IP:    (detection failed, using existing tfvars)"
fi
if [ -n "$VERSION_OVERRIDE" ]; then
    echo "Version:      $VERSION_OVERRIDE (override)"
fi
echo "========================================================"
echo ""

cd "$TF_DIR"

# -------------------------------------------------------------------------
# Update CIDRs with detected public IP
# -------------------------------------------------------------------------
if [ -n "$DETECTED_IP" ]; then
    TFVARS_FILE="terraform.tfvars"
    if [ -f "$TFVARS_FILE" ]; then
        echo "Updating $TFVARS_FILE with public IP $DETECTED_IP/32 ..."
        sed -i "s|^ssh_cidrs.*=.*|ssh_cidrs     = [\"$DETECTED_IP/32\"]       # Auto-updated by terraform-deploy.sh|" "$TFVARS_FILE"
        sed -i "s|^grafana_cidrs.*=.*|grafana_cidrs = [\"$DETECTED_IP/32\"]       # Auto-updated by terraform-deploy.sh|" "$TFVARS_FILE"
        sed -i "s|^client_cidrs.*=.*|client_cidrs  = [\"10.0.0.0/8\", \"$DETECTED_IP/32\"]  # Auto-updated by terraform-deploy.sh|" "$TFVARS_FILE"
        echo ""
    fi
fi

# -------------------------------------------------------------------------
# Step 1: Destroy (optional)
# -------------------------------------------------------------------------
if [ "$DESTROY" = true ]; then
    echo "[1/4] Destroying existing infrastructure..."
    echo "--------------------------------------------------------------"

    if [ "$AUTO_APPROVE" = true ]; then
        terraform destroy $TF_VAR_FLAGS -auto-approve
    else
        echo ""
        echo "WARNING: This will destroy ALL infrastructure in $ENVIRONMENT."
        echo "         EBS volumes will be lost. Back up data first."
        echo ""
        read -p "Type 'destroy' to confirm: " CONFIRM
        if [ "$CONFIRM" != "destroy" ]; then
            echo "Aborted."
            exit 1
        fi
        terraform destroy $TF_VAR_FLAGS
    fi

    echo ""
    echo "Destroy complete."
    echo ""
else
    echo "[1/4] Skipping destroy (use -d to destroy first)"
    echo ""
fi

# -------------------------------------------------------------------------
# Step 2: Init
# -------------------------------------------------------------------------
echo "[2/4] Initializing Terraform..."
echo "--------------------------------------------------------------"
terraform init
echo ""

# -------------------------------------------------------------------------
# Step 3: Plan
# -------------------------------------------------------------------------
echo "[3/4] Planning infrastructure changes..."
echo "--------------------------------------------------------------"
terraform plan $TF_VAR_FLAGS -out=tfplan
echo ""

if [ "$PLAN_ONLY" = true ]; then
    echo "Plan saved to: $TF_DIR/tfplan"
    echo "Review the plan above. Run without --plan-only to apply."
    exit 0
fi

# -------------------------------------------------------------------------
# Step 4: Apply
# -------------------------------------------------------------------------
echo "[4/4] Applying infrastructure changes..."
echo "--------------------------------------------------------------"

if [ "$AUTO_APPROVE" = true ]; then
    terraform apply tfplan
else
    echo ""
    read -p "Apply the plan above? (yes/no): " CONFIRM
    if [ "$CONFIRM" != "yes" ]; then
        echo "Aborted. Plan file saved at: $TF_DIR/tfplan"
        exit 1
    fi
    terraform apply tfplan
fi

echo ""

# -------------------------------------------------------------------------
# Save outputs
# -------------------------------------------------------------------------
echo "Saving Terraform outputs to $OUTPUT_FILE..."
terraform output -json > "$OUTPUT_FILE"

echo ""
echo "========================================================"
echo "Deployment Complete"
echo "========================================================"
echo ""
echo "Outputs saved to: $OUTPUT_FILE"
echo ""

# Display key outputs in a readable format
echo "Infrastructure Summary:"
echo "--------------------------------------------------------------"

# Extract and display key IPs using portable parsing
if command -v jq &> /dev/null; then
    FIX_IP=$(jq -r '.fix_acceptor_private_ip.value // empty' "$OUTPUT_FILE" 2>/dev/null)
    OUCH_IP=$(jq -r '.ouch_acceptor_private_ip.value // empty' "$OUTPUT_FILE" 2>/dev/null)
    AERON_IP=$(jq -r '.aeron_persistence_private_ip.value // empty' "$OUTPUT_FILE" 2>/dev/null)
    MON_IP=$(jq -r '.monitoring_public_ip.value // empty' "$OUTPUT_FILE" 2>/dev/null)
    GRAFANA=$(jq -r '.grafana_url.value // empty' "$OUTPUT_FILE" 2>/dev/null)

    printf "  %-25s %s\n" "FIX Acceptor:" "${FIX_IP:-N/A}"
    printf "  %-25s %s\n" "OUCH Acceptor:" "${OUCH_IP:-N/A}"
    printf "  %-25s %s\n" "Aeron Persistence:" "${AERON_IP:-N/A}"
    printf "  %-25s %s\n" "Monitoring (public):" "${MON_IP:-N/A}"
    printf "  %-25s %s\n" "Grafana:" "${GRAFANA:-N/A}"
else
    echo "  (install jq for formatted output summary)"
    echo "  Raw outputs in: $OUTPUT_FILE"
fi

echo "--------------------------------------------------------------"
echo ""
echo "Next step: ./scripts/deploy/setup-remote.sh -f $OUTPUT_FILE -i <pem-file>"
