resource "aws_lightsail_key_pair" "this" {
  name       = "${var.name}-key"
  public_key = file(pathexpand(var.ssh_public_key_path))
}

resource "aws_lightsail_instance" "this" {
  name              = var.name
  availability_zone = var.availability_zone
  blueprint_id      = var.blueprint_id
  bundle_id         = var.bundle_id
  key_pair_name     = aws_lightsail_key_pair.this.name

  # Docker 설치와 swap만 한다. 비밀값은 여기에 넣지 않는다 —
  # user_data는 GetInstance 권한만 있으면 누구나 읽을 수 있다.
  # 앱 배포와 .env 전달은 deploy/deploy.sh가 SSH로 한다.
  user_data = file("${path.module}/user_data.sh")

  add_on {
    type          = "AutoSnapshot"
    snapshot_time = var.auto_snapshot_time
    status        = "Enabled"
  }

  lifecycle {
    # user_data는 최초 부팅에만 실행된다. 바꿨다고 인스턴스(=DB)를 갈아엎지 않는다.
    ignore_changes = [user_data]
  }
}

resource "aws_lightsail_static_ip" "this" {
  name = "${var.name}-ip"
}

resource "aws_lightsail_static_ip_attachment" "this" {
  static_ip_name = aws_lightsail_static_ip.this.name
  instance_name  = aws_lightsail_instance.this.name
}

# 이 리소스는 인스턴스의 방화벽 규칙 전체를 대체한다. 기본으로 열려 있는 22번도 여기서 다시 정의한다.
resource "aws_lightsail_instance_public_ports" "this" {
  instance_name = aws_lightsail_instance.this.name

  port_info {
    protocol          = "tcp"
    from_port         = 22
    to_port           = 22
    cidrs             = var.ssh_allowed_cidrs
    cidr_list_aliases = ["lightsail-connect"]
  }

  port_info {
    protocol  = "tcp"
    from_port = 80
    to_port   = 80
    cidrs     = ["0.0.0.0/0"]
  }

  port_info {
    protocol  = "tcp"
    from_port = 443
    to_port   = 443
    cidrs     = ["0.0.0.0/0"]
  }
}
