########################################
# EC2 application server
########################################

# Latest official NixOS AMI for the target region.
# See https://nixos.org/download/#nixos-amazon for the current channel/owner.
data "aws_ami" "nixos" {
  most_recent = true
  owners      = ["427812963091"]

  filter {
    name   = "name"
    values = ["nixos/26.05*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }
}

resource "aws_key_pair" "app" {
  key_name   = "climate-changed"
  public_key = var.ssh_public_key
}

# CloudFront's origin-facing IP ranges, as an AWS-managed prefix list. We use
# this to only allow the app port to be reached from CloudFront, not the world.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "app" {
  name        = "climate-changed-app"
  description = "climate-changed application server"
  vpc_id      = aws_vpc.app.id

  # SSH access (restrict via var.ssh_allowed_cidr).
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_allowed_cidr]
  }

  # App port, reachable only from CloudFront's edge locations.
  ingress {
    description     = "App port from CloudFront"
    from_port       = var.app_port
    to_port         = var.app_port
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "climate-changed-app"
  }
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.nixos.id
  instance_type               = var.instance_type
  key_name                    = aws_key_pair.app.key_name
  subnet_id                   = aws_subnet.app.id
  vpc_security_group_ids      = [aws_security_group.app.id]
  associate_public_ip_address = true

  # Allow the instance to read/write the timeseries bucket without static keys.
  iam_instance_profile = aws_iam_instance_profile.app.name

  # Run on the spot market, bidding just above on-demand (see spot_max_price).
  # interruption_behavior = "stop" + persistent request means an interruption
  # only stops the instance (keeping its Elastic IP and instance ID); AWS
  # restarts it automatically when capacity is available again.
  instance_market_options {
    market_type = "spot"

    spot_options {
      max_price                      = var.spot_max_price
      instance_interruption_behavior = var.spot_interruption_behavior
      spot_instance_type             = "persistent"
    }
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = {
    Name = "climate-changed-app"
  }
}

########################################
# Stable Elastic IP
#
# The auto-assigned public IP changes on every stop/start, which breaks SSH
# and the CloudFront origin. An Elastic IP survives stop/start and reassociation
# to a replacement instance, so we pin the instance to it.
########################################

resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "climate-changed-app-eip"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

########################################
# Instance IAM role (S3 access)
########################################

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "climate-changed-app"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "data_bucket_access" {
  statement {
    actions = [
      "s3:ListBucket",
      "s3:GetBucketLocation",
      "s3:ListBucketMultipartUploads",
    ]
    resources = [aws_s3_bucket.data.arn]
  }

  statement {
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["${aws_s3_bucket.data.arn}/*"]
  }
}

resource "aws_iam_role_policy" "data_bucket_access" {
  name   = "climate-changed-data-bucket"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.data_bucket_access.json
}

resource "aws_iam_instance_profile" "app" {
  name = "climate-changed-app"
  role = aws_iam_role.app.name
}

########################################
# S3 bucket for timeseries data
########################################

resource "aws_s3_bucket" "data" {
  bucket = var.data_bucket_name
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket = aws_s3_bucket.data.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "data" {
  bucket = aws_s3_bucket.data.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
