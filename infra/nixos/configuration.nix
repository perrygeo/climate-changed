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

  # ---------------------------------------------------------------------------
  # climate-changed application service
  #
  # The uberjar is deployed manually (see `make deploy`) rather than built as a
  # nix package. Deployment uploads a timestamped jar to /var/lib/climate-changed
  # and repoints the `production.jar` symlink, which this unit executes.
  # ---------------------------------------------------------------------------
  systemd.services.climate-changed = {
    description = "climate-changed web application";
    wantedBy = [ "multi-user.target" ];
    after = [ "network.target" ];

    environment = {
      PORT = "9000";
    };

    serviceConfig = {
      # StateDirectory creates/owns /var/lib/climate-changed for us.
      StateDirectory = "climate-changed";
      WorkingDirectory = "/var/lib/climate-changed";
      ExecStart = "${pkgs.openjdk25_headless}/bin/java -jar /var/lib/climate-changed/production.jar";
      Restart = "on-failure";
      RestartSec = 5;
      # Only start once the symlink exists; avoids crash-looping before first deploy.
      ExecCondition = "${pkgs.coreutils}/bin/test -e /var/lib/climate-changed/production.jar";
    };
  };
}
