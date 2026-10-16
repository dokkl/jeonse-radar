#!/bin/bash
# Lightsail 최초 부팅 시 root로 한 번 실행된다. 로그: /var/log/cloud-init-output.log
# Lightsail은 자체 스크립트 뒤에 이어 붙여 sh(dash)로 실행하므로 shebang이 무시된다.
# bash 전용 문법(set -o pipefail 등)을 쓰지 않는다.
set -eux

timedatectl set-timezone Asia/Seoul

# 4GB 인스턴스에서 백필/집계 순간 피크를 버티기 위한 swap
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Docker Engine + compose plugin (공식 apt 저장소)
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker
usermod -aG docker ubuntu

install -d -o ubuntu -g ubuntu /opt/jeonse-radar

touch /var/lib/cloud/instance/jeonse-radar-ready
