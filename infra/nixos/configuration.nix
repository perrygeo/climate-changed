{
  config,
  pkgs,
  lib,
  modulesPath,
  ...
}:

let
  # -------------------------------------------------------------------------
  # ZeroFS / ZFS storage settings.
  #
  #   S3 bucket  --ZeroFS-->  /dev/nbd0 (block device)  --ZFS-->  era_ts
  #
  # Keep awsRegion/dataBucket in sync with infra/terraform (region and data_bucket_name).
  # -------------------------------------------------------------------------
  awsRegion = "us-east-1";
  dataBucket = "climate-changed-era5-timeseries-v1";
  ssmPasswordParam = "/climate-changed/zerofs/encryption-password";

  # ZeroFS keeps its objects under this prefix in the data bucket.
  zerofsStorageUrl = "s3://${dataBucket}/era_ts";

  # The NBD export is a sparse file under .nbd/ inside the ZeroFS filesystem.
  # Its size only caps the block device; S3 usage grows with data actually
  # written (both ZeroFS and ZFS write lazily).
  nbdExportName = "era_ts";
  nbdExportSize = "500G";
  zpoolName = "era_ts_pool";
  eraTsMount = "/var/lib/climate-changed/era_ts";
in
{
  imports = [
    (modulesPath + "/virtualisation/amazon-image.nix")
  ];

  system.stateVersion = "26.05";

  # Required by ZFS (and kept stable so the pool imports cleanly after a
  # rebuild onto a fresh instance).
  networking.hostId = "c1c0de01";

  # CloudFront reaches the origin on this port (matches var.app_port default).
  networking.firewall.allowedTCPPorts = [ 9000 ];

  # ---------------------------------------------------------------------------
  # ZFS + the kernel modules for the S3-backed block device.
  #
  # nbd: attach the ZeroFS export as /dev/nbd*. 9p/9pnet_fd: stock Linux v9fs,
  # used once at boot to create the NBD export file (see zerofs-export.service).
  # ---------------------------------------------------------------------------
  boot.supportedFilesystems = [ "zfs" ];
  boot.kernelModules = [
    "nbd"
    "9p"
    "9pnet_fd"
  ];

  boot.extraModprobeConfig = ''
    options nbd nbds_max=16
    # Cap ZFS ARC on the 2 GiB t3.small so the JVM and ZeroFS keep headroom.
    options zfs zfs_arc_max=536870912
  '';

  environment.systemPackages = [
    pkgs.zerofs
    pkgs.zfs
  ];

  # /var/lib/climate-changed must exist before ZFS mounts era_ts under it.
  systemd.tmpfiles.rules = [
    "d /var/lib/climate-changed 0755 root root -"
  ];

  # ---------------------------------------------------------------------------
  # ZeroFS server: exposes the data bucket as a filesystem, an NBD export, and
  # a loopback 9P endpoint used for one-time bootstrap.
  # ---------------------------------------------------------------------------
  environment.etc."zerofs/zerofs.toml" = {
    mode = "0600";
    text = ''
      [cache]
      dir = "/var/cache/zerofs"
      disk_size_gb = 2.0
      memory_size_gb = 0.25

      [storage]
      url = "${zerofsStorageUrl}"
      encryption_password = "''${ZEROFS_PASSWORD}"

      [servers.nbd]
      unix_socket = "/run/zerofs/zerofs.nbd.sock"

      [servers.ninep]
      addresses = [ "127.0.0.1:5564" ]

      [telemetry]
      enabled = false
    '';
  };

  # Fetch the ZeroFS encryption password from SSM Parameter Store (so it
  # survives instance rebuilds) into a transient env file.
  systemd.services.zerofs-secret = {
    description = "Fetch ZeroFS encryption password from SSM";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      RuntimeDirectory = "zerofs-secret";
      RuntimeDirectoryMode = "0700";
    };
    path = [ pkgs.coreutils ];
    script = ''
      umask 077
      pw="$(${lib.getExe' pkgs.awscli2 "aws"} ssm get-parameter \
        --name ${ssmPasswordParam} \
        --with-decryption \
        --query Parameter.Value \
        --output text \
        --region ${awsRegion})"
      printf 'ZEROFS_PASSWORD=%s\n' "$pw" > /run/zerofs-secret/zerofs.env
    '';
  };

  systemd.services.zerofs = {
    description = "ZeroFS server (S3-backed filesystem)";
    wantedBy = [ "multi-user.target" ];
    after = [
      "network-online.target"
      "zerofs-secret.service"
    ];
    wants = [ "network-online.target" ];
    requires = [ "zerofs-secret.service" ];
    serviceConfig = {
      ExecStart = "${lib.getExe pkgs.zerofs} run --config /etc/zerofs/zerofs.toml";
      EnvironmentFile = "-/run/zerofs-secret/zerofs.env";
      Restart = "always";
      RestartSec = 5;
      CacheDirectory = "zerofs";
      RuntimeDirectory = "zerofs";
      RuntimeDirectoryMode = "0700";
    };
  };

  # One-time bootstrap: create the sparse .nbd/<export> file (through a loopback
  # 9P mount) so nbd-client has an export to attach. Idempotent.
  systemd.services.zerofs-export = {
    description = "Create the ZeroFS NBD export file";
    after = [ "zerofs.service" ];
    requires = [ "zerofs.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [
      pkgs.coreutils
      pkgs.util-linux
    ];
    script = ''
      mnt=/mnt/zerofs-admin
      install -d "$mnt"
      for i in $(seq 1 60); do
        if mount -t 9p -o trans=tcp,port=5564,version=9p2000.L,access=user 127.0.0.1 "$mnt" 2>/dev/null; then
          break
        fi
        sleep 1
      done
      if ! mountpoint -q "$mnt"; then
        echo "zerofs-export: could not mount ZeroFS over 9P" >&2
        exit 1
      fi
      if [ ! -f "$mnt/.nbd/${nbdExportName}" ]; then
        install -d "$mnt/.nbd"
        truncate -s ${nbdExportSize} "$mnt/.nbd/${nbdExportName}"
      fi
      umount "$mnt"
    '';
  };

  # Attach the NBD export as /dev/nbd0. nbd-client (netlink) hands the socket
  # to the kernel and then exits 0; the kernel keeps the device attached. So
  # this is a one-shot connect, not a long-running daemon. The -check guard
  # makes re-runs (e.g. `nixos-rebuild switch`) idempotent instead of failing
  # with EBUSY ("nbd0 already in use").
  #
  # ExecStop disconnects /dev/nbd0 on shutdown so systemd-shutdown doesn't try
  # to sync a dead NBD backend after zerofs (the S3 server) is gone.
  # zerofs-zfs.service is PartOf this unit, so the ZFS pool is exported before
  # the device is disconnected — safe even on a partial restart.
  systemd.services.zerofs-nbd = {
    description = "Attach ZeroFS NBD export as /dev/nbd0";
    wantedBy = [ "multi-user.target" ];
    after = [
      "zerofs.service"
      "zerofs-export.service"
    ];
    requires = [
      "zerofs.service"
      "zerofs-export.service"
    ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      ExecStartPre = "${lib.getExe' pkgs.kmod "modprobe"} nbd";
      ExecStop = "-${lib.getExe' pkgs.nbd "nbd-client"} -d /dev/nbd0";
      TimeoutStopSec = 180;
    };
    path = [ pkgs.coreutils ];
    script = ''
      if ${lib.getExe' pkgs.nbd "nbd-client"} -check /dev/nbd0 >/dev/null 2>&1; then
        echo "zerofs-nbd: /dev/nbd0 already connected"
        exit 0
      fi
      ${lib.getExe' pkgs.nbd "nbd-client"} -unix /run/zerofs/zerofs.nbd.sock /dev/nbd0 -N ${nbdExportName}
    '';
  };

  # Import (or first-time create) the ZFS pool on /dev/nbd0 and mount era_ts.
  systemd.services.zerofs-zfs = {
    description = "Import/create the era_ts ZFS pool and mount it";
    wantedBy = [ "multi-user.target" ];
    after = [ "zerofs-nbd.service" ];
    requires = [ "zerofs-nbd.service" ];
    # Stop/restart together with the NBD attach so the pool is exported before
    # /dev/nbd0 is disconnected (see zerofs-nbd ExecStop).
    partOf = [ "zerofs-nbd.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      # Export the ZFS pool on shutdown, while zerofs (the S3 backend) is still
      # running. This cleanly unmounts era_ts; without it the filesystem
      # unmount runs after zerofs has stopped and hangs on the dead NBD device.
      ExecStop = [
        "-${lib.getExe' pkgs.zfs "zpool"} export ${zpoolName}"
        "-${lib.getExe' pkgs.zfs "zpool"} export -f ${zpoolName}"
      ];
      TimeoutStopSec = 180;
    };
    path = [ pkgs.coreutils ];
    script = ''
      # Wait for the NBD device to be attached *and* sized: right after
      # nbd-client connects, /dev/nbd0 exists but reports size 0 until the
      # kernel finishes the capacity change. Writing during that window fails
      # with ENOSPC.
      ready=0
      for i in $(seq 1 60); do
        sz="$(cat /sys/block/nbd0/size 2>/dev/null || true)"
        if [ -b /dev/nbd0 ] && [ -n "$sz" ] && [ "$sz" -gt 0 ]; then
          ready=1
          break
        fi
        sleep 1
      done
      if [ "$ready" != 1 ]; then
        echo "zerofs-zfs: /dev/nbd0 did not become ready" >&2
        exit 1
      fi

      zpool="${lib.getExe' pkgs.zfs "zpool"}"
      zfs="${lib.getExe' pkgs.zfs "zfs"}"

      if ! "$zpool" list ${zpoolName} >/dev/null 2>&1; then
        if ! "$zpool" import -d /dev/nbd0 ${zpoolName}; then
          if ! "$zpool" import -d /dev/nbd0 -f ${zpoolName}; then
            # First boot only: the mountpoint may still hold stale data from
            # the old local-disk era_ts. The S3-backed pool is the new source
            # of truth, so clear it before ZFS claims the mountpoint.
            rm -rf ${eraTsMount}
            "$zpool" create -o ashift=12 -O mountpoint=${eraTsMount} -O atime=off ${zpoolName} /dev/nbd0
          fi
        fi
      fi
      "$zfs" mount ${zpoolName} 2>/dev/null || true
    '';
  };

  # ---------------------------------------------------------------------------
  # climate-changed application service
  #
  # The uberjar is deployed manually (see `make deploy`) rather than built as a
  # nix package. Deployment uploads a timestamped jar to /var/lib/climate-changed
  # and repoints the `production.jar` symlink, which this unit executes.
  #
  # era_ts is the S3-backed ZFS mount; the app must not start before it.
  # ---------------------------------------------------------------------------
  systemd.services.climate-changed = {
    description = "climate-changed web application";
    wantedBy = [ "multi-user.target" ];
    after = [
      "network.target"
      "zerofs-zfs.service"
    ];
    requires = [ "zerofs-zfs.service" ];

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
  # Enable flakes and the new nix CLI permanently so that `nixos-rebuild
  # --flake`, `nix shell`, and `nix-shell` work without passing
  # `--extra-experimental-features 'nix-command flakes'` on every invocation.
  nix.settings.experimental-features = [
    "nix-command"
    "flakes"
  ];
}
