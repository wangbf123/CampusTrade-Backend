#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/campustrade}"
APP_USER="${APP_USER:-campustrade}"
SSH_PORT="${SSH_PORT:-22}"
ENABLE_UFW="${ENABLE_UFW:-false}"
DOCKER_REGISTRY_MIRRORS="${DOCKER_REGISTRY_MIRRORS:-}"

if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 root 权限执行: sudo bash deploy/server/bootstrap-ubuntu.sh"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y ca-certificates curl gnupg lsb-release ufw unzip jq

install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi

UBUNTU_CODENAME="$(
  . /etc/os-release
  echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}"
)"

cat >/etc/apt/sources.list.d/docker.list <<EOF
deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME} stable
EOF

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

if [ -n "${DOCKER_REGISTRY_MIRRORS}" ]; then
  IFS=',' read -ra MIRRORS <<< "${DOCKER_REGISTRY_MIRRORS}"
  install -m 0755 -d /etc/docker
  {
    echo '{'
    echo '  "registry-mirrors": ['
    for index in "${!MIRRORS[@]}"; do
      mirror="$(echo "${MIRRORS[$index]}" | xargs)"
      comma=","
      if [ "${index}" -eq "$((${#MIRRORS[@]} - 1))" ]; then
        comma=""
      fi
      echo "    \"${mirror}\"${comma}"
    done
    echo '  ]'
    echo '}'
  } >/etc/docker/daemon.json
  systemctl restart docker
fi

if ! id "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --shell /usr/sbin/nologin "${APP_USER}"
fi

mkdir -p "${APP_DIR}"
chown -R "${APP_USER}:${APP_USER}" "${APP_DIR}"

systemctl enable docker
systemctl start docker

if [ "${ENABLE_UFW}" = "true" ]; then
  ufw allow "${SSH_PORT}/tcp"
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw --force enable
fi

docker version
docker compose version

echo "服务器初始化完成。应用目录: ${APP_DIR}"
