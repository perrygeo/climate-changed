{ config, pkgs, lib, modulesPath, ... }:

{
  imports = [
    (modulesPath + "/virtualisation/amazon-image.nix")
  ];

  system.stateVersion = "26.05";

  # CloudFront reaches the origin on this port (matches var.app_port default).
  networking.firewall.allowedTCPPorts = [ 9000 ];

  # TODO: once app deployment is in scope, declare the uberjar as a package
  # and a systemd service here; nixos-rebuild will then manage it.
}
