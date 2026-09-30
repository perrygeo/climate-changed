variable "region" {
  description = "AWS region for the EC2 instance and S3 bucket."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type for the application server."
  type        = string
  default     = "t3.small"
}

variable "ssh_public_key" {
  description = "SSH public key material (e.g. contents of ~/.ssh/id_ed25519.pub) used to create the EC2 key pair."
  type        = string
}

variable "ssh_private_key_path" {
  description = "Local private key matching ssh_public_key, used by nixos-rebuild to SSH into the instance."
  type        = string
  default     = "~/.ssh/id_ed25519"
}

variable "ssh_allowed_cidr" {
  description = "CIDR block allowed to SSH into the instance. Set this to <your-ip>/32."
  type        = string
  default     = "0.0.0.0/0"
}

variable "app_port" {
  description = "Port the application listens on and that CloudFront forwards to."
  type        = number
  default     = 9000
}

variable "data_bucket_name" {
  description = "Globally-unique S3 bucket name to hold the timeseries data."
  type        = string
  default     = "climate-changed-timeseries"
}

variable "domain_name" {
  description = "Custom domain to serve the app from."
  type        = string
  default     = "climate-changed.org"
}

variable "enable_custom_domain" {
  description = <<-EOT
    Whether to attach the custom domain + ACM certificate to CloudFront.

    Leave this false for the first `terraform apply` so you get a working
    *.cloudfront.net URL immediately. Then request/validate the certificate
    (see README), set this to true, and apply again to serve climate-changed.org.
  EOT
  type        = bool
  default     = false
}
