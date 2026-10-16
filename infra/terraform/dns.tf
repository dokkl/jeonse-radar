# 도메인을 Route 53에서 등록할 때 자동 생성된 존. Terraform이 소유하지 않고 참조만 한다.
data "aws_route53_zone" "this" {
  name         = var.domain
  private_zone = false
}

resource "aws_route53_record" "apex" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.this.ip_address]
}

# www는 Caddy가 apex로 301 리다이렉트한다. 인증서 발급을 위해 같은 IP를 가리켜야 한다.
resource "aws_route53_record" "www" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "www.${var.domain}"
  type    = "A"
  ttl     = 300
  records = [aws_lightsail_static_ip.this.ip_address]
}
