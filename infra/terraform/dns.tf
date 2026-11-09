# 도메인을 Route 53에서 등록할 때 자동 생성된 존. Terraform이 소유하지 않고 참조만 한다.
data "aws_route53_zone" "this" {
  name         = var.zone_name
  private_zone = false
}

# 서비스 호스트(apt.seeplus.net). Caddy가 이 이름으로 인증서를 발급하므로 이 IP를 가리켜야 한다.
resource "aws_route53_record" "app" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.this.ip_address]
}
