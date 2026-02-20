# =============================================================================
# OmniBridge Production - Terraform Backend Configuration
# =============================================================================
# Remote state stored in S3 with DynamoDB locking to prevent concurrent
# modifications in team environments.
# =============================================================================

terraform {
  backend "s3" {
    bucket         = "omnibridge-terraform-state"
    key            = "production/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "omnibridge-terraform-locks"
  }
}
