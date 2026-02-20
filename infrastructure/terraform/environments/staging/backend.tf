# =============================================================================
# OmniBridge Staging - Terraform Backend Configuration
# =============================================================================
# Separate state key from production to ensure complete isolation.
# =============================================================================

terraform {
  backend "s3" {
    bucket         = "omnibridge-terraform-state"
    key            = "staging/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "omnibridge-terraform-locks"
  }
}
