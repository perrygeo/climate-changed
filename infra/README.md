# climate-changed infrastructure (Terraform ❤️ Nix)

To create the minimum production infrastructure on AWS,

- **EC2** spot `t3.medium` running the official NixOS AMI, SSH-able as `root`,
  with an instance profile that can read/write the timeseries bucket. The
  instance is bid on the spot market just above on-demand (see `spot_max_price`)
  and stops on interruption, restarting automatically when capacity returns. The
  system is configured by the flake in `../flake.nix`.
- **S3 bucket** for timeseries data (private, versioned, encrypted).
- **CloudFront** distribution that forwards to the EC2 instance on port `9000`.
  Caching is origin-controlled: responses are only cached when the origin sets
  `Cache-Control`/`Expires`, otherwise they are not cached. Today only the
  satellite tiles endpoint sets those headers.

Software deployment (CI, systemd units, app packaging) is intentionally out of
scope. This gets you a NixOS box you can SSH into, run something on port 9000,
and reach through CloudFront.

## Prerequisites

- Terraform >= 1.5
- Nix with flakes enabled (used by `nixos-rebuild` to configure the instance)
- AWS credentials in your environment (`aws configure` / `AWS_PROFILE` / env vars)
- An SSH public key, plus the matching private key locally

## Step 1 - First apply (get a working CloudFront URL)

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: set ssh_public_key, ssh_private_key_path,
# ssh_allowed_cidr, data_bucket_name
# keep enable_custom_domain = false for now

terraform init

aws login  # via browser
eval "$(aws configure export-credentials --format env)"
terraform apply
```

Terraform prints outputs including:

- `ssh_command` - SSH into the instance.
- `cloudfront_domain_name` - e.g. `d1234abcd.cloudfront.net`.
- `data_bucket` - the timeseries S3 bucket.

On Sep 30, first run:
```
Apply complete! Resources: 9 added, 0 changed, 0 destroyed.

Outputs:

acm_validation_records = []
cloudfront_domain_name = "d6afen5o55kcs.cloudfront.net"
data_bucket = "climate-changed-era5-timeseries-v1"
instance_public_dns = "ec2-52-204-129-175.compute-1.amazonaws.com"
instance_public_ip = "52.204.129.175"
ssh_command = "ssh root@52.204.129.175"
```

Verify end-to-end:

```bash
# SSH in
ssh root@<instance_public_ip>

# on the instance, serve something on the app port (fetch python3 on demand)
# (flakes/nix-command are enabled in the NixOS config, so no extra flags needed)
nix-shell -p python3 --run 'python3 -m http.server 9000'
```

Then open `https://d6afen5o55kcs.cloudfront.net/` in a browser - you should see the
directory listing served by the instance. (CloudFront redirects HTTP to HTTPS.)

## Rebuilding the NixOS configuration

To configure the instance in place, use `nixos-rebuild` using the flake at
`../flake.nix` (which imports `../nixos/configuration.nix`). Terraform runs this
automatically when the instance is first created.

To apply configuration changes:

- Edit `../flake.nix` / `../nixos/configuration.nix` and run `terraform apply`
  again - Terraform re-runs the rebuild against the same instance. It does **not**
  replace the EC2 instance.
- Force a rebuild without editing files:

  ```bash
  terraform apply -replace='terraform_data.nixos_rebuild'
  ```

- Or rebuild directly, without Terraform:

  ```bash
  cd ..
  NIX_SSHOPTS="-o StrictHostKeyChecking=accept-new -i $HOME/.ssh/id_ed25519" \
    nixos-rebuild switch --flake .#app --target-host root@<instance_public_ip>
  ```

## Step 2 - Attach climate-changed.org

CloudFront needs an ACM certificate (in `us-east-1`) for the custom domain.

1. Enable the domain and request the certificate:

   ```hcl
   # terraform.tfvars
   enable_custom_domain = true
   domain_name          = "climate-changed.org"
   ```

   ```bash
   terraform apply
   ```

   The apply will **pause** waiting for certificate validation and print the
   required DNS record(s) in the `acm_validation_records` output. (If it blocks,
   run `terraform apply` in one terminal and read the record from
   `terraform output acm_validation_records` - or run apply, note the record
   from the plan, add it, and re-apply.)

2. **Manually add the ACM validation record** at your DNS provider - a `CNAME`
   with the `name` and `value` from `acm_validation_records`. Once it
   propagates, AWS validates the cert and `terraform apply` completes.

3. **Manually point climate-changed.org at CloudFront.** Use the
   `cloudfront_domain_name` output as the target:

   - **Route 53 (recommended for an apex domain):** create an **A – Alias**
     record for `climate-changed.org` targeting the CloudFront distribution.
     (Alias records can sit at the zone apex; a plain CNAME cannot.)
   - **Other registrars:** apex `CNAME` is not valid DNS. Either use a registrar
     that supports `ALIAS`/`ANAME` flattening pointing to
     `<cloudfront_domain_name>`, or serve from `www.climate-changed.org` via a
     `CNAME` to `<cloudfront_domain_name>` and redirect the apex.

Here's how it looks on cloudflare

```
;;
;; Domain:     climate-changed.org.
;; Exported:   2026-09-30 21:14:32
;;
;; This file is intended for use for informational and archival
;; purposes ONLY and MUST be edited before use on a production
;; DNS server.  In particular, you must:
;;   -- update the SOA record with the correct authoritative name server
;;   -- update the SOA record with the contact e-mail address information
;;   -- update the NS record(s) with the authoritative name servers for this domain.
;;
;; For further information, please consult the BIND documentation
;; located on the following website:
;;
;; http://www.isc.org/
;;
;; And RFC 1035:
;;
;; http://www.ietf.org/rfc/rfc1035.txt
;;
;; Please note that we do NOT offer technical support for any use
;; of this zone data, the BIND name server, or any other third-party
;; DNS software.
;;
;; Use at your own risk.
;; SOA Record
climate-changed.org	3600	IN	SOA	justin.ns.cloudflare.com. dns.cloudflare.com. 2054246947 10000 2400 604800 3600

;; NS Records
climate-changed.org.	86400	IN	NS	justin.ns.cloudflare.com.
climate-changed.org.	86400	IN	NS	mimi.ns.cloudflare.com.

;; CNAME Records
_08fa4616a889452f49bd90a34abed0d5.climate-changed.org.	1	IN	CNAME	_863c7b2352825c1264895f12ff99fc2b.wzccmgtwzk.acm-validations.aws. ; cf_tags=cf-proxied:false
climate-changed.org.	1	IN	CNAME	d6afen5o55kcs.cloudfront.net. ; cf_tags=cf-proxied:false
www.climate-changed.org.	1	IN	CNAME	d6afen5o55kcs.cloudfront.net. ; cf_tags=cf-proxied:false
```

1. Browse to `https://climate-changed.org/`.

```
nix-shell -p openjdk25_headless --run 'PORT=9000 java -jar climate-changed-0.1.0-SNAPSHOT-standalone.jar'
```

---


## Caching

`cloudfront.tf` defines `aws_cloudfront_cache_policy.origin_controlled` with
`default_ttl = 0`: CloudFront caches a response only when the origin returns
`Cache-Control`/`Expires`, and uses the origin's TTL otherwise. The satellite
tiles endpoint (`/tiles/s2cloudless/{z}/{y}/{x}`) is currently the only handler
that returns a `Cache-Control` header, so it is the only thing cached at the
edge.

To cache another endpoint, have its Clojure handler return a `Cache-Control`
header - no Terraform change is needed. To cache a path unconditionally instead,
add an `ordered_cache_behavior` for that path with an explicit cache policy.

## Tear down

```bash
terraform destroy
```

Note: an S3 bucket must be empty to be destroyed. Empty
`data_bucket_name` first if you've uploaded data.
