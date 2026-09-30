########################################
# ACM certificate (only when the custom domain is enabled)
#
# CloudFront requires the certificate to live in us-east-1. Validation is done
# via DNS: after `terraform apply` creates the certificate, add the CNAME
# record(s) shown in the `acm_validation_records` output to your DNS provider.
# Terraform will wait until the certificate is validated.
########################################

resource "aws_acm_certificate" "cert" {
  count             = var.enable_custom_domain ? 1 : 0
  provider          = aws.us_east_1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_acm_certificate_validation" "cert" {
  count           = var.enable_custom_domain ? 1 : 0
  provider        = aws.us_east_1
  certificate_arn = aws_acm_certificate.cert[0].arn
}

########################################
# Managed policies: no caching, forward everything to the origin.
########################################

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewerExceptHostHeader"
}

########################################
# CloudFront distribution
########################################

locals {
  origin_id = "climate-changed-ec2"
}

resource "aws_cloudfront_distribution" "app" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "climate-changed"

  # Attach the custom domain only when enabled.
  aliases = var.enable_custom_domain ? [var.domain_name] : []

  origin {
    origin_id   = local.origin_id
    domain_name = aws_instance.app.public_dns

    custom_origin_config {
      http_port              = var.app_port
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = local.origin_id
    viewer_protocol_policy = "redirect-to-https"

    allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods  = ["GET", "HEAD"]

    # Caching disabled for now; swap these policies (or set an explicit
    # min/default/max TTL) once the app is stable to turn caching on.
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = var.enable_custom_domain ? null : true
    acm_certificate_arn            = var.enable_custom_domain ? aws_acm_certificate_validation.cert[0].certificate_arn : null
    ssl_support_method             = var.enable_custom_domain ? "sni-only" : null
    minimum_protocol_version       = var.enable_custom_domain ? "TLSv1.2_2021" : "TLSv1"
  }
}
