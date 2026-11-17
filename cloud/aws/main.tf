terraform {
  required_version = ">= 1.6"
  required_providers { aws = { source = "hashicorp/aws", version = "~> 5.0" } }
}
provider "aws" { region = var.region }
variable "region" { type = string }
variable "bucket_name" { type = string }
variable "domain" { type = string }
variable "application_origin" { type = string } # HTTPS ALB hostname; no scheme
variable "certificate_arn" { type = string }    # ACM certificate in us-east-1 for domain
variable "origin_verify_secret" {
  type      = string
  sensitive = true
  validation {
    condition     = can(regex("^[A-Za-z0-9]{32,128}$", var.origin_verify_secret))
    error_message = "Use a random 32-128 character alphanumeric origin verification secret."
  }
}
variable "cloudfront_public_key_pem" { type = string }
resource "aws_s3_bucket" "media" {
  bucket        = var.bucket_name
  force_destroy = false
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "media" {
  bucket = aws_s3_bucket.media.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_versioning" "media" {
  bucket = aws_s3_bucket.media.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_cors_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  cors_rule {
    allowed_origins = ["https://${var.domain}"]
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 600
  }
}
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  rule {
    id     = "abort-orphan-multipart"
    status = "Enabled"
    filter { prefix = "sources/" }
    abort_incomplete_multipart_upload { days_after_initiation = 2 }
  }
}
resource "aws_cloudfront_public_key" "media" {
  encoded_key = var.cloudfront_public_key_pem
  name_prefix = "streaming-"
}
resource "aws_cloudfront_key_group" "media" {
  name  = "${var.bucket_name}-viewers"
  items = [aws_cloudfront_public_key.media.id]
}
resource "aws_cloudfront_origin_access_control" "media" {
  name                              = "${var.bucket_name}-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
data "aws_cloudfront_cache_policy" "disabled" { name = "Managed-CachingDisabled" }
data "aws_cloudfront_cache_policy" "media" { name = "Managed-CachingOptimized" }
data "aws_cloudfront_origin_request_policy" "application" { name = "Managed-AllViewerExceptHostHeader" }
resource "aws_cloudfront_distribution" "media" {
  enabled         = true
  aliases         = [var.domain]
  is_ipv6_enabled = true
  price_class     = "PriceClass_100"
  origin {
    origin_id   = "application"
    domain_name = var.application_origin
    custom_header {
      name  = "X-Streaming-Origin"
      value = var.origin_verify_secret
    }
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }
  origin {
    origin_id                = "private-media"
    domain_name              = aws_s3_bucket.media.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.media.id
  }
  default_cache_behavior {
    target_origin_id         = "application"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.application.id
  }
  ordered_cache_behavior {
    path_pattern           = "/videos/*"
    target_origin_id       = "private-media"
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.media.id
    trusted_key_groups     = [aws_cloudfront_key_group.media.id]
    compress               = false
  }
  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
  restrictions {
    geo_restriction { restriction_type = "none" }
  }
}
resource "aws_s3_bucket_policy" "media" {
  bucket = aws_s3_bucket.media.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "OnlyCloudFrontOrigin", Effect = "Allow", Principal = { Service = "cloudfront.amazonaws.com" }, Action = "s3:GetObject", Resource = "${aws_s3_bucket.media.arn}/videos/*", Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.media.arn } } },
    { Sid = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [aws_s3_bucket.media.arn, "${aws_s3_bucket.media.arn}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } }
  ] })
}
output "distribution_id" { value = aws_cloudfront_distribution.media.id }
output "key_id" { value = aws_cloudfront_public_key.media.id }
output "bucket" { value = aws_s3_bucket.media.id }
