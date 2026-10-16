terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 개인 프로젝트라 state는 로컬에 둔다 (.gitignore 등록됨).
  # 여러 사람이 다루게 되면 S3 backend로 옮긴다.
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile

  # 기본 프로파일이 회사 계정이다. 실수로 엉뚱한 계정에 만들지 않도록 고정한다.
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project   = "jeonse-radar"
      ManagedBy = "terraform"
    }
  }
}
