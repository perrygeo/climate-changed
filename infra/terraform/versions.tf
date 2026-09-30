terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }
}

# Default provider: everything that is region-scoped lives here.
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "climate-changed"
      ManagedBy = "terraform"
    }
  }
}

# CloudFront requires its ACM certificate to live in us-east-1, regardless of
# where the rest of the infrastructure runs. This aliased provider is used only
# for the ACM certificate.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project   = "climate-changed"
      ManagedBy = "terraform"
    }
  }
}
