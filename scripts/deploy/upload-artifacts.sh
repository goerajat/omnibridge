#!/bin/bash
# =============================================================================
# Upload Artifacts to S3
# =============================================================================
# Uploads distribution archives to S3 for deployment.
#
# Usage: ./upload-artifacts.sh [options] [component...]
#
# Components: all (default), exchange-simulator, fix-samples, ouch-samples,
#             aeron-remote-store, log-viewer, omniview, mcp-server
#
# Options:
#   -v, --version     Version string (default: auto-detected from pom.xml)
#   -b, --bucket      S3 bucket name (default: omnibridge-artifacts)
#   -e, --environment S3 environment prefix (default: production)
#   -n, --dry-run     Show what would be uploaded without uploading
#
# Examples:
#   ./upload-artifacts.sh                           # Upload all, auto-detect version
#   ./upload-artifacts.sh -v 1.0.2-SNAPSHOT         # Upload all with explicit version
#   ./upload-artifacts.sh exchange-simulator         # Upload only exchange-simulator
#   ./upload-artifacts.sh aeron-remote-store log-viewer  # Upload multiple components
#   ./upload-artifacts.sh -n all                    # Dry run
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Default values
S3_BUCKET="omnibridge-artifacts"
ENVIRONMENT="production"
DRY_RUN=false
VERSION=""
COMPONENTS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -b|--bucket)
            S3_BUCKET="$2"
            shift 2
            ;;
        -e|--environment)
            ENVIRONMENT="$2"
            shift 2
            ;;
        -n|--dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            echo "Upload Artifacts to S3"
            echo ""
            echo "Usage: $0 [options] [component...]"
            echo ""
            echo "Components: all (default), exchange-simulator, fix-samples, ouch-samples,"
            echo "            aeron-remote-store, log-viewer, omniview"
            echo ""
            echo "Options:"
            echo "  -v, --version     Version string (default: auto-detected from pom.xml)"
            echo "  -b, --bucket      S3 bucket name (default: omnibridge-artifacts)"
            echo "  -e, --environment S3 environment prefix (default: production)"
            echo "  -n, --dry-run     Show what would be uploaded without uploading"
            echo ""
            echo "Examples:"
            echo "  $0                                        # Upload all, auto-detect version"
            echo "  $0 -v 1.0.2-SNAPSHOT                      # Upload all with explicit version"
            echo "  $0 exchange-simulator                      # Upload only exchange-simulator"
            echo "  $0 aeron-remote-store log-viewer           # Upload multiple components"
            echo "  $0 -n all                                  # Dry run"
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: $1"
            echo "Run '$0 --help' for usage."
            exit 1
            ;;
        *)
            COMPONENTS+=("$1")
            shift
            ;;
    esac
done

# Default to "all" if no components specified
if [ ${#COMPONENTS[@]} -eq 0 ]; then
    COMPONENTS=("all")
fi

# Auto-detect version from pom.xml if not provided
if [ -z "$VERSION" ]; then
    VERSION=$(grep -m1 '<version>' "$SCRIPT_DIR/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
    if [ -z "$VERSION" ]; then
        echo "Error: Could not detect version from pom.xml. Use -v to specify."
        exit 1
    fi
fi

# Locate AWS CLI
AWS_CMD="aws"
if ! command -v aws &>/dev/null; then
    if [ -x "/c/Program Files/Amazon/AWSCLIV2/aws.exe" ]; then
        AWS_CMD="/c/Program Files/Amazon/AWSCLIV2/aws.exe"
    else
        echo "Error: AWS CLI not found. Install it or add it to PATH."
        exit 1
    fi
fi

# Convert a path to native format for the AWS CLI (handles Cygwin/MSYS2 on Windows)
to_native_path() {
    if command -v cygpath &>/dev/null; then
        cygpath -w "$1"
    else
        echo "$1"
    fi
}

S3_PREFIX="s3://$S3_BUCKET/omnibridge/$ENVIRONMENT"

echo "========================================================"
echo "Upload Artifacts to S3"
echo "========================================================"
echo "Version:     $VERSION"
echo "S3 Bucket:   $S3_BUCKET"
echo "Environment: $ENVIRONMENT"
echo "S3 Path:     $S3_PREFIX/"
echo "Dry Run:     $DRY_RUN"
echo "========================================================"
echo ""

# Define artifact mappings: component -> relative path to dist archive
declare -A ARTIFACTS
ARTIFACTS[exchange-simulator]="apps/exchange-simulator/target/exchange-simulator-${VERSION}-dist.tar.gz"
ARTIFACTS[fix-samples]="apps/fix-samples/target/fix-samples-${VERSION}-dist.tar.gz"
ARTIFACTS[ouch-samples]="apps/ouch-samples/target/ouch-samples-${VERSION}-dist.tar.gz"
ARTIFACTS[aeron-remote-store]="apps/aeron-remote-store/target/aeron-remote-store-${VERSION}-dist.tar.gz"
ARTIFACTS[log-viewer]="apps/log-viewer/target/log-viewer-${VERSION}-dist.tar.gz"
ARTIFACTS[omniview]="omniview/target/omniview-${VERSION}-dist.tar.gz"
ARTIFACTS[mcp-server]="mcp-server/target/mcp-server-${VERSION}-dist.tar.gz"

ALL_COMPONENTS=(exchange-simulator fix-samples ouch-samples aeron-remote-store log-viewer omniview mcp-server)

# Resolve "all" to the full list
RESOLVED=()
for comp in "${COMPONENTS[@]}"; do
    if [ "$comp" = "all" ]; then
        RESOLVED+=("${ALL_COMPONENTS[@]}")
    elif [ -n "${ARTIFACTS[$comp]+x}" ]; then
        RESOLVED+=("$comp")
    else
        echo "Error: Unknown component '$comp'"
        echo "Available: ${ALL_COMPONENTS[*]}"
        exit 1
    fi
done

# Deduplicate
declare -A SEEN
UNIQUE=()
for comp in "${RESOLVED[@]}"; do
    if [ -z "${SEEN[$comp]+x}" ]; then
        SEEN[$comp]=1
        UNIQUE+=("$comp")
    fi
done

# Upload each artifact
UPLOADED=0
FAILED=0
SKIPPED=0

for comp in "${UNIQUE[@]}"; do
    ARTIFACT_PATH="$SCRIPT_DIR/${ARTIFACTS[$comp]}"
    ARTIFACT_NAME=$(basename "$ARTIFACT_PATH")

    if [ ! -f "$ARTIFACT_PATH" ]; then
        echo "SKIP  $comp - artifact not found: ${ARTIFACTS[$comp]}"
        echo "      (build with: mvn package -pl apps/$comp -am -DskipTests)"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    SIZE=$(du -h "$ARTIFACT_PATH" | cut -f1)

    if [ "$DRY_RUN" = true ]; then
        echo "DRY   $comp ($SIZE) -> $S3_PREFIX/$ARTIFACT_NAME"
        UPLOADED=$((UPLOADED + 1))
    else
        echo -n "UP    $comp ($SIZE) -> $S3_PREFIX/$ARTIFACT_NAME ... "
        if "$AWS_CMD" s3 cp "$(to_native_path "$ARTIFACT_PATH")" "$S3_PREFIX/$ARTIFACT_NAME" --quiet; then
            echo "OK"
            UPLOADED=$((UPLOADED + 1))
        else
            echo "FAILED"
            FAILED=$((FAILED + 1))
        fi
    fi
done

echo ""
echo "========================================================"
echo "Results: $UPLOADED uploaded, $SKIPPED skipped, $FAILED failed"
echo "========================================================"

if [ $FAILED -gt 0 ]; then
    exit 1
fi
