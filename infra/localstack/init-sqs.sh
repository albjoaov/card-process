#!/bin/bash
set -euo pipefail

QUEUE_NAME="card-issuance-queue"
DLQ_NAME="card-issuance-dlq"
ENDPOINT="http://localhost:4566"
ACCOUNT_ID="000000000000"

awslocal sqs create-queue --queue-name "${DLQ_NAME}"

DLQ_ARN="arn:aws:sqs:us-east-1:${ACCOUNT_ID}:${DLQ_NAME}"

awslocal sqs create-queue \
  --queue-name "${QUEUE_NAME}" \
  --attributes "{\"VisibilityTimeout\":\"30\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"

echo "SQS provisioned: ${QUEUE_NAME} -> ${DLQ_NAME} (maxReceiveCount=5)"
