{
  config,
  pkgs,
  lib,
  modulesPath,
  ...
}:

{
  imports = [
    (modulesPath + "/virtualisation/amazon-image.nix")
  ];

  system.stateVersion = "26.05";

  # CloudFront reaches the origin on this port (matches var.app_port default).
  networking.firewall.allowedTCPPorts = [ 9000 ];
}
