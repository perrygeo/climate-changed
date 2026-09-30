output "instance_public_ip" {
  description = "Public IP of the EC2 instance (use this to SSH in)."
  value       = aws_instance.app.public_ip
}

output "instance_public_dns" {
  description = "Public DNS name of the EC2 instance."
  value       = aws_instance.app.public_dns
}

output "ssh_command" {
  description = "Convenience SSH command (assumes the key added above)."
  value       = "ssh root@${aws_instance.app.public_ip}"
}

output "cloudfront_domain_name" {
  description = "CloudFront distribution domain. Point your DNS CNAME/ALIAS here."
  value       = aws_cloudfront_distribution.app.domain_name
}

output "data_bucket" {
  description = "S3 bucket holding the timeseries data."
  value       = aws_s3_bucket.data.bucket
}

output "acm_validation_records" {
  description = "DNS records to create to validate the ACM certificate (only when enable_custom_domain = true)."
  value = var.enable_custom_domain ? [
    for o in aws_acm_certificate.cert[0].domain_validation_options : {
      name  = o.resource_record_name
      type  = o.resource_record_type
      value = o.resource_record_value
    }
  ] : []
}
