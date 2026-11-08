#!/usr/bin/env bash
# Generates the ECDSA P-256 keypair used to sign emergency-vehicle credentials.
set -euo pipefail
openssl ecparam -name prime256v1 -genkey -noout -out ec.pem
openssl pkcs8 -topk8 -nocrypt -in ec.pem -out ec_pkcs8.pem
echo "== Supabase secret CRED_SIGNING_KEY_PKCS8_B64 (keep private):"
openssl pkcs8 -topk8 -nocrypt -in ec.pem -outform DER | base64 -w0; echo
echo "== GitHub secret CRED_PUBLIC_KEY_B64 (public, X.509 SPKI):"
openssl ec -in ec.pem -pubout -outform DER 2>/dev/null | base64 -w0; echo
rm -f ec.pem ec_pkcs8.pem
