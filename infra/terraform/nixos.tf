########################################
# NixOS rebuild
#
# The instance boots the official NixOS AMI and is then configured in place
# with the flake in ../ (infra/). Changing any of the flake files below makes
# Terraform replace this small terraform_data resource, which re-runs the
# provisioner against the same EC2 instance — the instance itself is NOT
# replaced. To force a rebuild without editing files, run:
#
#   terraform apply -replace='terraform_data.nixos_rebuild'
########################################

resource "terraform_data" "nixos_rebuild" {
  depends_on = [aws_instance.app]

  triggers_replace = {
    instance_id = aws_instance.app.id
    flake       = filesha256("${path.module}/../flake.nix")
    lock        = filesha256("${path.module}/../flake.lock")
    config      = filesha256("${path.module}/../nixos/configuration.nix")
  }

  provisioner "local-exec" {
    working_dir = "${path.module}/.."
    command     = <<-EOT
      set -euo pipefail
      key="${pathexpand(var.ssh_private_key_path)}"
      ssh_opts="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=5 -i $key"
      for i in $(seq 1 30); do
        if ssh $ssh_opts root@${aws_instance.app.public_ip} true 2>/dev/null; then
          NIX_SSHOPTS="$ssh_opts" \
            nixos-rebuild switch \
              --flake .#app \
              --target-host root@${aws_instance.app.public_ip}
          exit 0
        fi
        echo "waiting for SSH on ${aws_instance.app.public_ip} ($i/30)..."
        sleep 5
      done
      echo "instance did not become reachable over SSH" >&2
      exit 1
    EOT
  }
}
