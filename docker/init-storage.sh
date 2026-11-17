#!/bin/sh
set -eu
awslocal s3api create-bucket --bucket media
awslocal s3api put-bucket-cors --bucket media --cors-configuration '{"CORSRules":[{"AllowedOrigins":["http://localhost:8090"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":600}]}'
awslocal s3api put-bucket-lifecycle-configuration --bucket media --lifecycle-configuration '{"Rules":[{"ID":"abort-incomplete","Status":"Enabled","Filter":{"Prefix":"sources/"},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":2}}]}'
