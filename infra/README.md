# climate-changed infrastructure (Terraform ❤️ Nix)

To create the minimum production infrastructure on AWS,

- **EC2** `t3.small` running the official NixOS AMI, SSH-able as `root`, with
  an instance profile that can read/write the timeseries bucket. The system is
  configured by the flake in `../flake.nix`.
- **S3 bucket** for timeseries data (private, versioned, encrypted).
- **CloudFront** distribution that forwards to the EC2 instance on port `9000`.
  Caching is disabled but the distribution is structured so you can turn it on
  later by swapping the cache policy.

Software deployment (CI, systemd units, app packaging) is intentionally out of
scope. This gets you a NixOS box you can SSH into, run something on port 9000,
and reach through CloudFront.

## Prerequisites

- Terraform >= 1.5
- Nix with flakes enabled (used by `nixos-rebuild` to configure the instance)
- AWS credentials in your environment (`aws configure` / `AWS_PROFILE` / env vars)
- An SSH public key, plus the matching private key locally

## Step 1 — First apply (get a working CloudFront URL)

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

- `ssh_command` — SSH into the instance.
- `cloudfront_domain_name` — e.g. `d1234abcd.cloudfront.net`.
- `data_bucket` — the timeseries S3 bucket.

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
nix-shell -p python3 --run 'python3 -m http.server 9000'
```

Then open `https://<cloudfront_domain_name>/` in a browser — you should see the
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

## Step 2 — Attach climate-changed.org

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
   `terraform output acm_validation_records` — or run apply, note the record
   from the plan, add it, and re-apply.)

2. **Manually add the ACM validation record** at your DNS provider — a `CNAME`
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

4. Browse to `https://climate-changed.org/`.

## Turning caching on later

In `cloudfront.tf`, the `default_cache_behavior` uses the managed
`Managed-CachingDisabled` policy. Replace `cache_policy_id` with a caching
policy (e.g. `Managed-CachingOptimized`) or a custom `aws_cloudfront_cache_policy`
with your desired TTLs, then `terraform apply`.

## Tear down

```bash
terraform destroy
```

Note: an S3 bucket must be empty to be destroyed. Empty
`data_bucket_name` first if you've uploaded data.
