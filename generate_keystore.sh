#!/bin/bash
# Generate keystore for APK signing
# Run this once, then add secrets to GitHub repository

set -e

KEYSTORE_FILE="release.keystore"
ALIAS="system-service"
VALIDITY=10000  # ~27 years

echo "[+] Generating release keystore..."
echo "[+] Keystore: $KEYSTORE_FILE"
echo "[+] Alias: $ALIAS"
echo ""

# Generate keystore
keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity $VALIDITY \
  -keystore "$KEYSTORE_FILE" \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=System Service, OU=Android, O=SystemService, L=Unknown, ST=Unknown, C=US" \
  -deststoretype pkcs12

echo ""
echo "[+] Keystore generated: $KEYSTORE_FILE"
echo ""
echo "[+] Convert to base64 for GitHub Secrets:"
base64 -w 0 "$KEYSTORE_FILE"
echo ""
echo ""
echo "[+] Add these secrets to GitHub Repository Settings → Secrets → Actions:"
echo "    KEYSTORE_BASE64  = (output above)"
echo "    KEY_ALIAS        = $ALIAS"
echo "    KEYSTORE_PASSWORD = your_keystore_password"
echo "    KEY_PASSWORD     = your_key_password"
echo ""
echo "[!] IMPORTANT: Save the keystore file and passwords securely!"
echo "[!] Without them, you cannot update the app on devices."