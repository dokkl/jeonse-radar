variable "aws_profile" {
  description = "hoon-admin3 사용자에 매핑된 AWS CLI 프로파일"
  type        = string
  default     = "starter"
}

variable "aws_account_id" {
  description = "배포 대상 계정. 다른 계정 자격증명으로 실행하면 provider가 거부한다"
  type        = string
  default     = "378040395734"
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "availability_zone" {
  type    = string
  default = "ap-northeast-2a"
}

variable "name" {
  description = "Lightsail 리소스 이름 접두사"
  type        = string
  default     = "jeonse-radar"
}

variable "bundle_id" {
  # MySQL(실거래 400만 행, 버퍼 풀 1G) + JVM + Caddy를 한 대에 올린다.
  # 2GB(small_3_0)는 야간 집계의 중위값 쿼리에서 메모리가 빠듯하다.
  description = "Lightsail 번들. medium_3_0 = 4GB RAM / 2vCPU / 80GB SSD, 월 $24"
  type        = string
  default     = "medium_3_0"
}

variable "blueprint_id" {
  type    = string
  default = "ubuntu_24_04"
}

variable "ssh_public_key_path" {
  type    = string
  default = "~/.ssh/jeonse-radar.pub"
}

variable "ssh_allowed_cidrs" {
  description = "SSH(22) 허용 대역. 브라우저 SSH(lightsail-connect)는 별도로 항상 허용된다"
  type        = list(string)
}

variable "domain" {
  description = "Route 53 호스티드 존이 이미 있는 도메인"
  type        = string
  default     = "seeplus.net"
}

variable "auto_snapshot_time" {
  # DB가 인스턴스 디스크에 있으므로 일일 스냅샷이 사실상의 백업이다.
  # UTC 기준. 18:00 UTC = 03:00 KST (02:00 수집과 04:00 집계 사이).
  description = "자동 스냅샷 시각 (UTC, HH:00)"
  type        = string
  default     = "18:00"
}
