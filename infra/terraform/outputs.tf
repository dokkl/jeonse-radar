output "static_ip" {
  value = aws_lightsail_static_ip.this.ip_address
}

output "ssh" {
  value = "ssh -i ~/.ssh/jeonse-radar ubuntu@${aws_lightsail_static_ip.this.ip_address}"
}

output "url" {
  value = "https://${var.domain}"
}
