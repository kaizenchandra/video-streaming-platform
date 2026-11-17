#!/bin/sh
set -eu
: "${S3_BUCKET:?Set a dedicated staging bucket}"
: "${S3_EXPECTED_OWNER:?Set the twelve digit bucket owner account ID}"
: "${AWS_REGION:?Set the bucket region}"
rm -f target/provider-certification.json
export PROVIDER_CERTIFY=true
mvn -Dtest=ProviderIT -Dtest.excluded=none test
# A certification result is written only after all assertions succeed.
test -f target/provider-certification.json
cat target/provider-certification.json
